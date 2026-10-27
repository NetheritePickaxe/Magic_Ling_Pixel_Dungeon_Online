package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 主机会话：内嵌 TCP 服务器 + 命令转发 + 状态广播。
 *
 * 线程模型：
 *  - 网络 IO 全部在 accept/reader 线程里；
 *  - 所有对游戏状态的读写都发生在渲染线程（onFrame 里），与单人输入同一条路径；
 *  - reader 线程只负责把收到的命令丢进队列。
 */
public final class HostSession {

	private final int port;
	private final int maxPlayers;
	private final boolean pvp;

	private volatile boolean running;

	private ServerSocket server;
	private Thread acceptThread;

	private final LocalTurns localTurns = new LocalTurns();

	private final List<HostPlayer> players = new CopyOnWriteArrayList<>();
	private final ConcurrentLinkedQueue<PendingCommand> commands = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<HostPlayer> joinQueue = new ConcurrentLinkedQueue<>();

	private volatile int nextPlayerId = 1;

	private long seq = 0;

	//增量同步用的上一帧状态
	private final Map<String, CharState> lastChars = new HashMap<>();
	private long lastHeapSig = 0;
	private int lastMapHash = 0;
	private long lastDiffAt = 0;
	private int lastDepth = Integer.MIN_VALUE;
	private int lastBranch = Integer.MIN_VALUE;
	private boolean needsFullSync = true;

	public HostSession( int port, int maxPlayers, boolean pvp ) {
		this.port = port;
		this.maxPlayers = maxPlayers;
		this.pvp = pvp;
	}

	public int port() {
		return port;
	}

	public boolean pvp() {
		return pvp;
	}

	public LocalTurns localTurns() {
		return localTurns;
	}

	public int playerCount() {
		return players.size();
	}

	/** 只读视图，供怪物目标选择使用 */
	public List<HostPlayer> playersView() {
		return players;
	}

	// ---------------------------------------------------------------- 生命周期

	public void start() throws IOException {
		server = new ServerSocket();
		server.setReuseAddress( true );
		server.bind( new InetSocketAddress( port ), 8 );
		running = true;

		acceptThread = new Thread( this::acceptLoop, "MLPD Lan Accept" );
		acceptThread.setDaemon( true );
		acceptThread.start();
	}

	public void stop() {
		running = false;
		try {
			if (server != null) server.close();
		} catch (IOException ignored) {
		}
		for (HostPlayer p : players) {
			p.conn.close();
		}
		players.clear();
		commands.clear();
		joinQueue.clear();
		lastChars.clear();
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket socket = server.accept();
				Thread t = new Thread( () -> handleConnection( socket ), "MLPD Lan Peer" );
				t.setDaemon( true );
				t.start();
			} catch (IOException e) {
				if (running) {
					//端口被占用之类的错误在 start() 里已经暴露，这里只处理 accept 中断
					break;
				}
			}
		}
	}

	private void handleConnection( Socket socket ) {
		LanConnection conn = null;
		HostPlayer player = null;
		try {
			conn = new LanConnection( socket );

			Protocol.Frame first = conn.receive();
			if (first == null || first.type != Protocol.C_HELLO) {
				conn.close();
				return;
			}

			Bundle hello = Protocol.decode( first.payload );

			if (hello.getInt( Protocol.K_VERSION ) != Protocol.VERSION) {
				reject( conn, "协议版本不一致（房主 " + Protocol.VERSION + "，房客 "
						+ hello.getInt( Protocol.K_VERSION ) + "）" );
				return;
			}
			if (hello.getInt( Protocol.K_APP ) != Game.versionCode) {
				reject( conn, "游戏版本不一致，请使用同一版本联机" );
				return;
			}
			if (players.size() >= maxPlayers) {
				reject( conn, "房间已满" );
				return;
			}

			String name = hello.getString( Protocol.K_NAME );
			if (name == null || name.isEmpty()) name = "冒险者";

			com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass heroClass;
			try {
				heroClass = com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass
						.valueOf( hello.getString( Protocol.K_CLASS ) );
			} catch (Exception e) {
				heroClass = com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.WARRIOR;
			}

			int id = nextPlayerId++;
			String token = Long.toHexString( System.nanoTime() ^ (id * 0x9E3779B97F4A7C15L) );

			player = new HostPlayer( id, name, token, heroClass, conn );
			players.add( player );

			Bundle welcome = new Bundle();
			welcome.put( Protocol.K_PLAYER_ID, id );
			welcome.put( Protocol.K_TOKEN, token );
			welcome.put( Protocol.K_HOST_ID, 0 );
			welcome.put( Protocol.K_PVP, pvp );
			welcome.put( Protocol.K_DEPTH, Dungeon.depth );
			welcome.put( Protocol.K_BRANCH, Dungeon.branch );
			welcome.put( Protocol.K_PLAYERS, playerList() );
			conn.send( Protocol.S_WELCOME, welcome );

			joinQueue.add( player );

			readLoop( player );

		} catch (Exception e) {
			//连接异常当作掉线处理
		} finally {
			if (player != null) {
				players.remove( player );
				broadcastNotice( player.name + " 离开了游戏" );
			}
			if (conn != null) conn.close();
		}
	}

	private void readLoop( HostPlayer player ) throws IOException {
		while (running) {
			Protocol.Frame frame = player.conn.receive();
			if (frame == null) return;

			switch (frame.type) {
				case Protocol.C_COMMAND: {
					Bundle b = Protocol.decode( frame.payload );
					commands.add( new PendingCommand( player, b ) );
					break;
				}
				case Protocol.C_RESYNC: {
					joinQueue.add( player );
					break;
				}
				case Protocol.C_PING: {
					player.conn.send( Protocol.S_PONG, new Bundle() );
					break;
				}
				default:
					break;
			}
		}
	}

	private void reject( LanConnection conn, String reason ) {
		Bundle b = new Bundle();
		b.put( Protocol.K_REASON, reason );
		conn.send( Protocol.S_REJECT, b );
		conn.close();
	}

	private Bundle playerList() {
		Bundle list = new Bundle();
		for (HostPlayer p : players) {
			Bundle entry = new Bundle();
			entry.put( Protocol.K_PLAYER_ID, p.id );
			entry.put( Protocol.K_NAME, p.name );
			list.put( String.valueOf( p.id ), entry );
		}
		return list;
	}

	/** 远端玩家阵亡 */
	public void onPlayerDied( HostLanHero hero ) {
		for (HostPlayer p : players) {
			if (p.hero == hero) {
				broadcastNotice( p.name + " 阵亡了" );
				return;
			}
		}
	}

	public void broadcastNotice( String text ) {
		if (players.isEmpty()) return;
		Bundle b = new Bundle();
		b.put( Protocol.K_TEXT, text );
		for (HostPlayer p : players) {
			p.conn.send( Protocol.S_NOTICE, b );
		}
	}

	// ---------------------------------------------------------------- 帧驱动（渲染线程）

	/** GameScene 创建完成（含换层后重建）：把远端英雄重新挂回调度并安排一次全量同步 */
	public void onSceneCreated() {
		localTurns.reset();
		if (Dungeon.hero == null || Dungeon.level == null || GameScene.scene == null) return;

		for (HostPlayer p : players) {
			HostLanHero hero = p.hero;
			if (hero == null) continue;

			boolean validPos = hero.pos >= 0 && hero.pos < Dungeon.level.length()
					&& Dungeon.level.passable[hero.pos];
			if (!validPos) {
				hero.pos = spawnPos();
			}

			if (!Actor.all().contains( hero )) {
				Actor.add( hero );
			}
			if (hero.sprite == null) {
				LanHeroSprite.create( hero );
			}
			GameScene.scene.lanAddCharSprite( hero );
			hero.parkNow();
		}

		needsFullSync = true;
	}

	public void onFrame() {
		if (!running) return;
		if (Dungeon.hero == null || Dungeon.level == null || GameScene.scene == null) return;

		try {
			if (Dungeon.depth != lastDepth || Dungeon.branch != lastBranch || needsFullSync) {
				lastDepth = Dungeon.depth;
				lastBranch = Dungeon.branch;
				if (settled()) {
					needsFullSync = false;
					for (HostPlayer p : players) {
						if (p.hero != null) sendSnapshot( p );
					}
					lastChars.clear();
					lastHeapSig = heapSignature();
					lastMapHash = java.util.Arrays.hashCode( Dungeon.level.map );
				}
				return;
			}

			//新玩家加入：等世界静止后再生成角色并下发快照
			if (!joinQueue.isEmpty() && settled()) {
				HostPlayer p;
				while ((p = joinQueue.poll()) != null) {
					spawnHero( p );
					sendSnapshot( p );
					broadcastNotice( p.name + " 加入了游戏" );
				}
				if (!lastChars.isEmpty()) {
					//强制重算一遍，把新角色广播给其它人
					lastChars.clear();
				}
				lastHeapSig = heapSignature();
				lastMapHash = java.util.Arrays.hashCode( Dungeon.level.map );
				return;
			}

			drainCommands();

			updateRemoteVisibility();

			if (!settled()) return;
			Bundle delta = buildDelta();
			if (delta != null) {
				for (HostPlayer p : players) {
					p.conn.send( Protocol.S_DELTA, delta );
				}
			}
		} catch (Exception e) {
			//单帧出错不应打断游戏
			Log.warn( "host frame error: " + e );
		}
	}

	private void updateRemoteVisibility() {
		if (Dungeon.level.heroFOV == null) return;
		for (HostPlayer p : players) {
			HostLanHero h = p.hero;
			if (h == null || h.sprite == null) continue;
			h.sprite.visible = h.pos >= 0 && h.pos < Dungeon.level.heroFOV.length
					&& Dungeon.level.heroFOV[h.pos];
		}
	}

	private boolean settled() {
		Hero hostHero = Dungeon.hero;
		if (hostHero == null || !hostHero.ready) return false;
		for (HostPlayer p : players) {
			HostLanHero h = p.hero;
			if (h != null && !h.idle()) return false;
		}
		return true;
	}

	private void drainCommands() {
		PendingCommand cmd;
		while ((cmd = commands.poll()) != null) {
			HostLanHero hero = cmd.player.hero;
			if (hero == null) continue;
			if (hero.isAlive() == false) continue;

			Bundle b = cmd.body;
			switch (b.getInt( Protocol.K_KIND )) {
				case Protocol.CMD_CELL: {
					int cell = b.getInt( Protocol.K_CELL );
					if (cell < 0 || cell >= Dungeon.level.length()) break;
					if (Dungeon.level.getTransition( cell ) != null) {
						notice( cmd.player, "换层请由房主发起" );
						break;
					}
					hero.queueCell( cell );
					break;
				}
				case Protocol.CMD_WAIT:
					hero.queueRest( false );
					break;
				case Protocol.CMD_REST:
					hero.queueRest( true );
					break;
				case Protocol.CMD_ITEM: {
					int index = b.getInt( Protocol.K_INDEX );
					String cls = b.getString( Protocol.K_CLASS );
					String action = b.getString( Protocol.K_ACTION );

					Item target = null;
					int i = 0;
					for (Item item : hero.belongings) {
						if (i++ == index) {
							target = item;
							break;
						}
					}
					if (target == null || !target.getClass().getName().equals( cls )) {
						notice( cmd.player, "物品已变化，请重试" );
						break;
					}
					if (!hero.useItem( target, action )) {
						notice( cmd.player, "当前无法使用物品" );
					}
					break;
				}
				default:
					break;
			}
		}
	}

	private void notice( HostPlayer p, String text ) {
		Bundle b = new Bundle();
		b.put( Protocol.K_TEXT, text );
		p.conn.send( Protocol.S_NOTICE, b );
	}

	// ---------------------------------------------------------------- 角色生成

	private void spawnHero( HostPlayer player ) {
		if (player.hero != null) {
			//重连：把角色重新放回世界
			HostLanHero hero = player.hero;
			hero.pos = spawnPos();
			hero.attach();
			if (hero.sprite == null) LanHeroSprite.create( hero );
			GameScene.scene.lanAddCharSprite( hero );
			return;
		}

		final HostLanHero hero = new HostLanHero();
		hero.playerId = player.id;

		//initHero / live 内部大量使用静态 Dungeon.hero,这里临时替换以让初始物品进入新角色
		Hero prev = Dungeon.hero;
		try {
			Dungeon.hero = hero;
			player.heroClass.initHero( hero );
			hero.live();
		} finally {
			Dungeon.hero = prev;
		}

		hero.pos = spawnPos();
		hero.parkNow();

		LanHeroSprite.create( hero );
		Actor.add( hero );
		GameScene.scene.lanAddCharSprite( hero );

		player.hero = hero;
	}

	private int spawnPos() {
		Level level = Dungeon.level;
		int base = Dungeon.hero.pos;
		for (int i : com.watabou.utils.PathFinder.NEIGHBOURS8) {
			int cell = base + i;
			if (cell < 0 || cell >= level.length()) continue;
			if (!level.passable[cell]) continue;
			if (Actor.findChar( cell ) != null) continue;
			return cell;
		}
		int pos = level.randomRespawnCell( null );
		return pos < 0 ? base : pos;
	}

	// ---------------------------------------------------------------- 快照

	private void sendSnapshot( HostPlayer player ) {
		try {
			if (player.hero == null) return;

			Dungeon.saveAll();

			Bundle game = FileUtils.bundleFromFile( GamesInProgress.gameFile( GamesInProgress.curSlot ) );
			Bundle level = FileUtils.bundleFromFile(
					GamesInProgress.depthFile( GamesInProgress.curSlot, Dungeon.depth, Dungeon.branch ) );

			game.put( "hero", plainHeroBundle( player.hero ) );
			game.put( Protocol.K_DEPTH, Dungeon.depth );
			game.put( Protocol.K_BRANCH, Dungeon.branch );

			Bundle snap = new Bundle();
			snap.put( Protocol.K_SEQ, ++seq );
			snap.put( Protocol.K_DEPTH, Dungeon.depth );
			snap.put( Protocol.K_BRANCH, Dungeon.branch );
			snap.put( Protocol.K_HOST_ID, 0 );
			snap.put( Protocol.K_PVP, pvp );
			snap.put( Protocol.K_GAME, game );
			snap.put( Protocol.K_LEVEL, level );

			Bundle list = new Bundle();
			{
				Bundle hostEntry = new Bundle();
				hostEntry.put( Protocol.K_PLAYER_ID, 0 );
				hostEntry.put( Protocol.K_NAME, Dungeon.hero.name() );
				hostEntry.put( Protocol.K_HERO, plainHeroBundle( Dungeon.hero ) );
				list.put( "0", hostEntry );
			}
			for (HostPlayer p : players) {
				if (p.hero == null) continue;
				Bundle entry = new Bundle();
				entry.put( Protocol.K_PLAYER_ID, p.id );
				entry.put( Protocol.K_NAME, p.name );
				entry.put( Protocol.K_HERO, plainHeroBundle( p.hero ) );
				list.put( String.valueOf( p.id ), entry );
			}
			snap.put( Protocol.K_PLAYERS, list );

			player.conn.send( Protocol.S_SNAPSHOT, snap );
		} catch (Exception e) {
			Log.warn( "snapshot failed: " + e );
		}
	}

	static Bundle plainHeroBundle( Hero hero ) {
		Bundle b = new Bundle();
		b.put( "__className", Hero.class.getName() );
		hero.storeInBundle( b );
		return b;
	}

	// ---------------------------------------------------------------- 增量

	private Bundle buildDelta() {
		long now = System.currentTimeMillis();
		if (now - lastDiffAt < 33) return null;
		lastDiffAt = now;

		Map<String, Char> current = new LinkedHashMap<>();
		for (Char ch : Actor.chars()) {
			//已死亡并被移出调度集合的角色不再下发
			current.put( uidOf( ch ), ch );
		}

		Bundle changed = new Bundle();
		Bundle added = new Bundle();
		ArrayList<String> removed = new ArrayList<>();

		for (Map.Entry<String, Char> e : current.entrySet()) {
			Char ch = e.getValue();
			CharState prev = lastChars.get( e.getKey() );
			int pos = ch.pos, hp = ch.HP, ht = ch.HT;
			boolean alive = ch.isAlive();

			if (prev == null) {
				if (!(ch instanceof Hero)) {
					Bundle holder = new Bundle();
					holder.put( "c", ch );
					added.put( e.getKey(), holder );
				}
				writeState( changed, e.getKey(), pos, hp, ht, alive );
				lastChars.put( e.getKey(), new CharState( pos, hp, ht, alive ) );
			} else if (prev.pos != pos || prev.hp != hp || prev.ht != ht || prev.alive != alive) {
				writeState( changed, e.getKey(), pos, hp, ht, alive );
				prev.pos = pos;
				prev.hp = hp;
				prev.ht = ht;
				prev.alive = alive;
			}
		}

		for (Iterator<Map.Entry<String, CharState>> it = lastChars.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, CharState> e = it.next();
			if (!current.containsKey( e.getKey() )) {
				removed.add( e.getKey() );
				it.remove();
			}
		}

		long heapSig = heapSignature();
		boolean heapsDirty = heapSig != lastHeapSig;
		int mapHash = java.util.Arrays.hashCode( Dungeon.level.map );
		boolean mapDirty = mapHash != lastMapHash;

		boolean changedAny = !changed.getKeys().isEmpty() || !added.getKeys().isEmpty()
				|| !removed.isEmpty() || heapsDirty || mapDirty;

		if (!changedAny) return null;

		Bundle delta = new Bundle();
		delta.put( Protocol.K_SEQ, ++seq );
		delta.put( Protocol.K_DEPTH, Dungeon.depth );
		delta.put( Protocol.K_BRANCH, Dungeon.branch );
		delta.put( Protocol.K_CHARS, changed );
		if (!added.getKeys().isEmpty()) delta.put( Protocol.K_NEW, added );
		if (!removed.isEmpty()) {
			delta.put( Protocol.K_REMOVED, removed.toArray( new String[0] ) );
		}
		if (mapDirty) {
			delta.put( Protocol.K_MAP, Dungeon.level.map.clone() );
			lastMapHash = mapHash;
		}
		if (heapsDirty) {
			delta.put( Protocol.K_HEAPS, heapBundle() );
			lastHeapSig = heapSig;
		}

		lastDepth = Dungeon.depth;
		lastBranch = Dungeon.branch;
		return delta;
	}

	private static void writeState( Bundle target, String uid, int pos, int hp, int ht, boolean alive ) {
		Bundle s = new Bundle();
		s.put( "p", pos );
		s.put( "h", hp );
		s.put( "t", ht );
		s.put( "a", alive );
		target.put( uid, s );
	}

	static String uidOf( Char ch ) {
		if (ch instanceof HostLanHero) return "p" + ((HostLanHero) ch).playerId;
		if (ch instanceof Hero) return "p0";
		return "a" + ch.id();
	}

	private long heapSignature() {
		long sig = 17;
		for (Heap h : Dungeon.level.heaps.valueList()) {
			sig = sig * 31 + h.pos;
			sig = sig * 31 + h.type.ordinal();
			for (Item i : h.items) {
				sig = sig * 31 + i.getClass().getName().hashCode();
				sig = sig * 31 + i.quantity();
			}
		}
		return sig;
	}

	private Bundle heapBundle() {
		Bundle bundle = new Bundle();
		for (Heap h : Dungeon.level.heaps.valueList()) {
			Bundle holder = new Bundle();
			holder.put( "h", h );
			bundle.put( String.valueOf( h.pos ), holder );
		}
		return bundle;
	}

	// ---------------------------------------------------------------- 内部结构

	private static final class CharState {
		int pos, hp, ht;
		boolean alive;

		CharState( int pos, int hp, int ht, boolean alive ) {
			this.pos = pos;
			this.hp = hp;
			this.ht = ht;
			this.alive = alive;
		}
	}

	private static final class PendingCommand {
		final HostPlayer player;
		final Bundle body;

		PendingCommand( HostPlayer player, Bundle body ) {
			this.player = player;
			this.body = body;
		}
	}

}
