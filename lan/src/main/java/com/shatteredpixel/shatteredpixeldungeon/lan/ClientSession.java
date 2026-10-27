package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 房客会话。
 *
 * 职责：
 *  - 连接主机、握手、接收全量快照与增量事件；
 *  - 本地不跑 Actor 逻辑线程（GameScene 侧由 LanHooks.suppressActorThread 关闭）；
 *  - 输入全部转成命令发给主机，本地只做表现。
 */
public final class ClientSession {

	private final String host;
	private final int port;
	private final String playerName;
	private final HeroClass heroClass;

	private volatile LanConnection conn;
	private volatile boolean running;

	private volatile int myPlayerId = -1;
	private volatile String token;

	private volatile boolean inGame = false;
	private volatile boolean resyncRequested = false;
	private volatile long lastResyncAt = 0;
	private volatile boolean switchingScene = false;
	private volatile long lastPingAt = 0;

	//网络线程 -> 渲染线程
	private final ConcurrentLinkedQueue<Runnable> toRenderThread = new ConcurrentLinkedQueue<>();

	private final Map<String, Char> entities = new HashMap<>();
	private Bundle playersBundle;

	private int appliedSeq = 0;

	public ClientSession( String host, int port, String playerName, HeroClass heroClass ) {
		this.host = host;
		this.port = port;
		this.playerName = playerName;
		this.heroClass = heroClass;
	}

	public void connect() {
		running = true;
		Thread t = new Thread( this::runConnection, "MLPD Lan Client" );
		t.setDaemon( true );
		t.start();
	}

	public void stop() {
		running = false;
		LanConnection c = conn;
		if (c != null) c.close();
		conn = null;
		inGame = false;
		entities.clear();
	}

	public boolean inGame() {
		return inGame;
	}

	// ---------------------------------------------------------------- 网络线程

	private void runConnection() {
		try {
			Socket socket = new Socket();
			socket.connect( new InetSocketAddress( host, port ), 5000 );
			LanConnection c = new LanConnection( socket );
			conn = c;

			Bundle hello = new Bundle();
			hello.put( Protocol.K_VERSION, Protocol.VERSION );
			hello.put( Protocol.K_APP, Game.versionCode );
			hello.put( Protocol.K_NAME, playerName );
			hello.put( Protocol.K_CLASS, heroClass == null ? HeroClass.WARRIOR.name() : heroClass.name() );
			c.send( Protocol.C_HELLO, hello );

			while (running) {
				Protocol.Frame frame = c.receive();
				if (frame == null) break;

				switch (frame.type) {
					case Protocol.S_WELCOME: {
						Bundle b = Protocol.decode( frame.payload );
						myPlayerId = b.getInt( Protocol.K_PLAYER_ID );
						token = b.getString( Protocol.K_TOKEN );
						toastAsync( "已连接到房主" );
						break;
					}
					case Protocol.S_REJECT: {
						Bundle b = Protocol.decode( frame.payload );
						final String reason = b.getString( Protocol.K_REASON );
						enterFailure( reason.isEmpty() ? "房主拒绝了连接" : reason );
						return;
					}
					case Protocol.S_SNAPSHOT: {
						final Bundle snap = Protocol.decode( frame.payload );
						post( () -> installSnapshot( snap ) );
						break;
					}
					case Protocol.S_DELTA: {
						final Bundle delta = Protocol.decode( frame.payload );
						post( () -> applyDelta( delta ) );
						break;
					}
					case Protocol.S_NOTICE: {
						Bundle b = Protocol.decode( frame.payload );
						final String text = b.getString( Protocol.K_TEXT );
						post( () -> GLog.i( text ) );
						break;
					}
					case Protocol.S_PONG:
						break;
					default:
						break;
				}

				long now = System.currentTimeMillis();
				if (now - lastPingAt > 5000) {
					lastPingAt = now;
					c.send( Protocol.C_PING, new Bundle() );
				}
			}

			if (running) {
				post( () -> {
					GLog.n( "与房主的连接断开了" );
					Lan.leaveClient();
					Game.switchScene( com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class );
				} );
			}

		} catch (Exception e) {
			Log.warn( "connect failed: " + e );
			enterFailure( "无法连接到房主（" + host + ":" + port + "）" );
		}
	}

	private void enterFailure( final String message ) {
		running = false;
		post( () -> {
			Lan.leaveClient();
			GLog.n( message );
			if (GameScene.scene != null) {
				Ui.show( new com.shatteredpixel.shatteredpixeldungeon.windows.WndError( message ) );
			} else {
				Game.switchScene( com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class );
			}
		} );
	}

	private void toastAsync( final String text ) {
		post( () -> GLog.i( text ) );
	}

	private void post( Runnable r ) {
		toRenderThread.add( r );
		Game.runOnRenderThread( new Callback() {
			@Override
			public void call() {
				drain();
			}
		} );
	}

	/** 渲染线程：把网络线程排队的操作执行掉 */
	private void drain() {
		Runnable r;
		while ((r = toRenderThread.poll()) != null) {
			try {
				r.run();
			} catch (Exception e) {
				Log.error( e );
			}
		}
	}

	// ---------------------------------------------------------------- 快照

	private void installSnapshot( Bundle snap ) {
		if (switchingScene) {
			//已经在换场景，等这次完成后再处理
			return;
		}
		switchingScene = true;
		try {
			playersBundle = snap.getBundle( Protocol.K_PLAYERS );
			int depth = snap.getInt( Protocol.K_DEPTH );
			int branch = snap.getInt( Protocol.K_BRANCH );

			LanSnapshot.writeGame( Lan.CLIENT_SLOT, snap.getBundle( Protocol.K_GAME ) );
			LanSnapshot.writeLevel( Lan.CLIENT_SLOT, depth, branch, snap.getBundle( Protocol.K_LEVEL ) );

			GamesInProgress.setUnknown( Lan.CLIENT_SLOT );
			GamesInProgress.curSlot = Lan.CLIENT_SLOT;

			if (GamesInProgress.check( Lan.CLIENT_SLOT ) == null) {
				switchingScene = false;
				enterFailure( "房主发来的存档无法读取" );
				return;
			}

			InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
			Game.switchScene( InterlevelScene.class );

		} catch (Exception e) {
			Log.error( e );
			switchingScene = false;
			enterFailure( "接收房主的世界快照失败" );
		}
	}

	/** GameScene 创建完成：把其它玩家挂进本地世界 */
	public void onSceneCreated() {
		if (!Lan.isClient()) return;
		if (Dungeon.hero == null || Dungeon.level == null) return;

		inGame = true;
		entities.clear();

		//自己的英雄
		if (myPlayerId >= 0) {
			entities.put( "p" + myPlayerId, Dungeon.hero );
		}
		Dungeon.hero.ready = true;

		switchingScene = false;

		//其它玩家
		if (playersBundle != null) {
			for (String key : playersBundle.getKeys()) {
				Bundle entry = playersBundle.getBundle( key );
				int pid = entry.getInt( Protocol.K_PLAYER_ID );
				if (pid == myPlayerId) continue;
				spawnRemoteHero( pid, entry.getBundle( Protocol.K_HERO ) );
			}
		}

		//本层怪物
		for (Mob mob : Dungeon.level.mobs) {
			entities.put( "a" + mob.id(), mob );
		}

		Dungeon.observe();
		GLog.i( "已进入联机游戏" );
	}

	private void spawnRemoteHero( int pid, Bundle heroBundle ) {
		try {
			Hero h = new Hero();
			h.restoreFromBundle( heroBundle );
			h.ready = true;

			Actor.add( h );
			LanHeroSprite.create( h );
			if (GameScene.scene != null) GameScene.scene.lanAddCharSprite( h );

			entities.put( "p" + pid, h );
		} catch (Exception e) {
			Log.warn( "spawn remote hero failed: " + e );
		}
	}

	// ---------------------------------------------------------------- 增量

	private void applyDelta( Bundle delta ) {
		if (!inGame || Dungeon.level == null || GameScene.scene == null) return;

		int seq = delta.getInt( Protocol.K_SEQ );
		if (seq <= appliedSeq) return;
		appliedSeq = seq;

		int depth = delta.getInt( Protocol.K_DEPTH );
		int branch = delta.getInt( Protocol.K_BRANCH );
		if (depth != Dungeon.depth || branch != Dungeon.branch) {
			requestResync();
			return;
		}

		String[] removed = delta.getStringArray( Protocol.K_REMOVED );
		if (removed != null) {
			for (String uid : removed) {
				Char ch = entities.remove( uid );
				removeEntity( ch );
			}
		}

		Bundle added = delta.getBundle( Protocol.K_NEW );
		if (!added.isNull()) {
			for (String uid : added.getKeys()) {
				try {
					Char ch = (Char) added.getBundle( uid ).get( "c" );
					if (ch == null) continue;
					registerChar( uid, ch );
				} catch (Exception e) {
					Log.warn( "add char failed: " + e );
				}
			}
		}

		Bundle chars = delta.getBundle( Protocol.K_CHARS );
		boolean selfMoved = false;
		if (!chars.isNull()) {
			for (String uid : chars.getKeys()) {
				Char ch = entities.get( uid );
				if (ch == null) continue;
				Bundle s = chars.getBundle( uid );
				selfMoved |= applyCharState( uid, ch, s );
			}
		}

		int[] map = delta.getIntArray( Protocol.K_MAP );
		if (map != null && map.length == Dungeon.level.map.length) {
			System.arraycopy( map, 0, Dungeon.level.map, 0, map.length );
			GameScene.scene.lanRefreshTerrain();
			selfMoved = true;
		}

		Bundle heaps = delta.getBundle( Protocol.K_HEAPS );
		if (!heaps.isNull()) {
			applyHeaps( heaps );
		}

		if (selfMoved) {
			Dungeon.observe();
		}
		updateHeroVisibility();
	}

	private boolean applyCharState( String uid, Char ch, Bundle s ) {
		int pos = s.getInt( "p" );
		int hp = s.getInt( "h" );
		int ht = s.getInt( "t" );
		boolean alive = s.getBoolean( "a" );

		boolean moved = false;

		if (ch.pos != pos) {
			int from = ch.pos;
			ch.pos = pos;
			if (ch.sprite != null) {
				if (Dungeon.level.adjacent( from, pos )) {
					ch.sprite.move( from, pos );
				} else {
					ch.sprite.place( pos );
				}
			}
			moved = true;
		}

		int dh = hp - ch.HP;
		boolean wasAlive = ch.HP > 0;
		ch.HT = ht;
		ch.HP = hp;
		if (dh != 0 && ch.sprite != null && Math.abs( dh ) < Math.max( 1, ch.HT )) {
			ch.sprite.showStatus( dh < 0 ? CharSprite.NEGATIVE : CharSprite.POSITIVE,
					String.valueOf( Math.abs( dh ) ) );
		}
		if (!alive) {
			ch.HP = 0;
			if (wasAlive && ch.sprite != null) {
				ch.sprite.die();
			}
		}

		return moved && ch == Dungeon.hero;
	}

	private void registerChar( String uid, Char ch ) {
		if (ch instanceof Mob) {
			Mob mob = (Mob) ch;
			Dungeon.level.mobs.add( mob );
			Actor.add( mob );
			GameScene.scene.lanAddCharSprite( mob );
		}
		entities.put( uid, ch );
	}

	private void removeEntity( Char ch ) {
		if (ch == null) return;

		if (ch == Dungeon.hero) {
			//自己阵亡：保留 sprite 播放死亡动画，只提示玩家
			if (ch.sprite != null) ch.sprite.die();
			Actor.remove( ch );
			GLog.n( "你已阵亡" );
			return;
		}
		if (ch instanceof Mob) {
			Dungeon.level.mobs.remove( ch );
		}
		if (ch.sprite != null) {
			ch.sprite.die();
			GameScene.scene.lanRemoveCharSprite( ch );
		}
		Actor.remove( ch );
	}

	private void applyHeaps( Bundle heaps ) {
		Dungeon.level.heaps.clear();
		for (String key : heaps.getKeys()) {
			try {
				Heap heap = (Heap) heaps.getBundle( key ).get( "h" );
				if (heap == null) continue;
				Dungeon.level.heaps.put( heap.pos, heap );
			} catch (Exception e) {
				Log.warn( "heap restore failed: " + e );
			}
		}
		GameScene.scene.lanRebuildHeaps();
	}

	private void updateHeroVisibility() {
		if (Dungeon.level == null || Dungeon.level.heroFOV == null) return;
		for (Char ch : entities.values()) {
			if (ch == Dungeon.hero || ch.sprite == null) continue;
			if (ch instanceof Hero) {
				ch.sprite.visible = ch.pos >= 0 && ch.pos < Dungeon.level.heroFOV.length
						&& Dungeon.level.heroFOV[ch.pos];
			}
		}
	}

	// ---------------------------------------------------------------- 输入 -> 命令

	public boolean sendCell( int cell ) {
		if (!inGame) return false;
		Bundle b = new Bundle();
		b.put( Protocol.K_KIND, Protocol.CMD_CELL );
		b.put( Protocol.K_CELL, cell );
		return send( b );
	}

	public boolean sendRest( boolean fullRest ) {
		if (!inGame) return false;
		Bundle b = new Bundle();
		b.put( Protocol.K_KIND, fullRest ? Protocol.CMD_REST : Protocol.CMD_WAIT );
		return send( b );
	}

	public boolean sendItem( Item item, String action ) {
		if (!inGame) return false;
		int index = -1;
		int i = 0;
		for (Item it : Dungeon.hero.belongings) {
			if (it == item) {
				index = i;
				break;
			}
			i++;
		}
		if (index < 0) return false;

		Bundle b = new Bundle();
		b.put( Protocol.K_KIND, Protocol.CMD_ITEM );
		b.put( Protocol.K_INDEX, index );
		b.put( Protocol.K_ACTION, action );
		b.put( Protocol.K_CLASS, item.getClass().getName() );
		return send( b );
	}

	private boolean send( Bundle body ) {
		LanConnection c = conn;
		if (c == null) return false;
		c.send( Protocol.C_COMMAND, body );
		return true;
	}

	private void requestResync() {
		long now = System.currentTimeMillis();
		if (resyncRequested || now - lastResyncAt < 3000) return;
		resyncRequested = true;
		lastResyncAt = now;
		LanConnection c = conn;
		if (c != null) c.send( Protocol.C_RESYNC, new Bundle() );
		GLog.w( "与房主状态不同步，正在重新同步…" );
	}

	public void onFrame() {
		if (!inGame) return;
		if (resyncRequested && Dungeon.hero != null && Dungeon.hero.ready) {
			resyncRequested = false;
		}
		updateHeroVisibility();
	}

	/** 场景被销毁（换场景/退出）：重置会话内的表现状态 */
	public void onSceneDestroyed() {
		inGame = false;
		for (Char ch : entities.values()) {
			if (ch instanceof Hero && ch != Dungeon.hero && ch.sprite != null) {
				ch.sprite.killAndErase();
			}
		}
		entities.clear();
	}
}
