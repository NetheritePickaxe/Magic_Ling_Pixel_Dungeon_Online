package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.BackupSaveScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.io.IOException;

/**
 * 联机门面：全局状态 + 会话生命周期。
 *
 * 与游戏其它部分的唯一接触面是 core 里的 LanHooks；
 * 本类只负责把钩子转发到当前会话（主机/房客）。
 */
public final class Lan {

	/** 房客接收快照用的内部槽位，不会出现在存档列表里 */
	public static final int CLIENT_SLOT = 999;

	public enum Mode { OFF, HOST, CLIENT }

	private static volatile Mode mode = Mode.OFF;

	private static volatile HostSession host;
	private static volatile ClientSession client;

	//房间设置（房主可改，开放时生效）
	public static int port = Protocol.DEFAULT_PORT;
	public static int maxPlayers = Protocol.MAX_PLAYERS;
	public static boolean pvp = false;
	//局部回合制：只推进活动玩家视野内的回合，视野外玩家不受影响（默认开启）
	public static boolean localTurns = true;

	public static void setLocalTurns( boolean enabled ) {
		if (localTurns == enabled) return;
		if (isHost() && !enabled) host().localTurns().disable();
		localTurns = enabled;
		if (isHost()) {
			host().broadcastNotice( enabled ? "房主开启了局部回合制" : "房主关闭了局部回合制" );
		}
	}

	// ---------------------------------------------------------------- 端口配置（持久化）

	private static final String PORT_FILE = "lan_port.txt";
	private static boolean portLoaded = false;

	/** 读取持久化端口（懒加载，确保在 Gdx 初始化之后调用） */
	static void loadConfig() {
		if (portLoaded) return;
		portLoaded = true;
		try {
			FileHandle f = Gdx.files.local( PORT_FILE );
			if (f.exists()) {
				int p = Integer.parseInt( f.readString().trim() );
				if (p > 0 && p <= 65535) port = p;
			}
		} catch (Exception ignored) {
		}
	}

	/** 设置并持久化监听端口 */
	public static void setPort( int p ) {
		if (p <= 0 || p > 65535) return;
		port = p;
		try {
			Gdx.files.local( PORT_FILE ).writeString( Integer.toString( p ), false );
		} catch (Exception ignored) {
		}
	}

	private Lan() {
	}

	public static Mode mode() {
		return mode;
	}

	public static boolean isHost() {
		return mode == Mode.HOST && host != null;
	}

	public static boolean isClient() {
		return mode == Mode.CLIENT && client != null;
	}

	public static HostSession host() {
		return host;
	}

	public static int protocolPort() {
		HostSession h = host;
		return h != null ? h.port() : port;
	}

	public static ClientSession client() {
		return client;
	}

	// ---------------------------------------------------------------- 主机

	public static void openHost() throws IOException {
		if (mode != Mode.OFF) throw new IllegalStateException( "session already active" );

		loadConfig();

		if (Dungeon.hero == null || Dungeon.level == null) {
			throw new IOException( "no game in progress" );
		}

		//开放前先存档，并另存一份到「存档备份」（文件名带 SP- 前缀），
		//联机过程出问题也能从备份恢复，单人槽位本身继续正常读写
		try {
			Dungeon.saveAll();
			BackupSaveScene.writeSlotToMLSP( GamesInProgress.curSlot, BackupSaveScene.AUTO_PREFIX );
			toast( "已把当前存档备份到「存档备份」" );
		} catch (Exception e) {
			toast( "联机前自动备份失败，可在「存档备份」中手动导出" );
		}

		HostSession session = new HostSession( port, maxPlayers, pvp );
		session.start();
		host = session;
		mode = Mode.HOST;
	}

	public static void closeHost() {
		HostSession session = host;
		host = null;
		mode = Mode.OFF;
		if (session != null) session.stop();

		try {
			Dungeon.saveAll();
		} catch (Exception ignored) {
		}
	}

	// ---------------------------------------------------------------- 房客

	public static void enterClient( ClientSession session ) {
		client = session;
		mode = Mode.CLIENT;
	}

	public static void leaveClient() {
		ClientSession session = client;
		client = null;
		mode = Mode.OFF;
		if (session != null) session.stop();
	}

	// ---------------------------------------------------------------- 供 HostLanHero 使用

	/**
	 * 唤醒主机 Actor 线程。
	 *
	 * 世界暂停时 Actor.current 停在主机英雄上（远端英雄永远不会停下来），
	 * 所以这里对主机英雄调用 next() 就能把 current 清空，GameScene 会在下一帧重新 notify。
	 */
	public static void wakeActorThread() {
		Hero hero = Dungeon.hero;
		if (hero != null) {
			try {
				hero.next();
			} catch (Exception ignored) {
			}
		}
	}

	public static void onRenderThread( Runnable r ) {
		Game.runOnRenderThread( r::run );
	}

	public static void toast( final String text ) {
		Game.runOnRenderThread( () -> {
			try {
				com.shatteredpixel.shatteredpixeldungeon.utils.GLog.i( text );
			} catch (Exception ignored) {
			}
		} );
	}

	static boolean sceneReady() {
		return GameScene.scene != null && Dungeon.hero != null && Dungeon.level != null;
	}

	static Bundle readBundleFile( String path ) throws IOException {
		return FileUtils.bundleFromFile( path );
	}
}
