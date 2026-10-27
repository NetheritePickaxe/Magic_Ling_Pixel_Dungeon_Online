package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.watabou.utils.Bundle;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 帧协议：
 *
 *   [4 字节 长度 n][1 字节 消息类型][n-1 字节 payload]
 *
 * payload 是一个 Bundle 的 UTF-8 JSON 文本。
 * 协议版本独立于游戏版本号，握手时强校验。
 */
public final class Protocol {

	public static final int VERSION = 1;

	public static final int DEFAULT_PORT = 47654;
	public static final int MAX_PLAYERS = 4;

	/** 单帧上限，快照里嵌套了整层的 Bundle，留够余量 */
	private static final int MAX_FRAME = 64 * 1024 * 1024;

	// ---- 房客 -> 主机
	public static final byte C_HELLO = 1;
	public static final byte C_COMMAND = 5;
	public static final byte C_RESYNC = 7;
	public static final byte C_PING = 9;

	// ---- 主机 -> 房客
	public static final byte S_WELCOME = 2;
	public static final byte S_REJECT = 3;
	public static final byte S_SNAPSHOT = 4;
	public static final byte S_DELTA = 6;
	public static final byte S_NOTICE = 8;
	public static final byte S_PONG = 10;
	public static final byte S_PLAYERS = 11;

	// ---- 命令种类
	public static final int CMD_CELL = 1;   // 格子行动：移动/攻击/拾取/交互/上下楼
	public static final int CMD_WAIT = 2;   // 等待一回合
	public static final int CMD_REST = 3;   // 休息
	public static final int CMD_ITEM = 4;   // 使用背包内第 index 个物品

	// ---- Bundle 键
	public static final String K_VERSION = "ver";
	public static final String K_APP = "app";
	public static final String K_NAME = "name";
	public static final String K_CLASS = "cls";
	public static final String K_REASON = "reason";
	public static final String K_PLAYER_ID = "pid";
	public static final String K_TOKEN = "token";
	public static final String K_HOST_ID = "host";
	public static final String K_PVP = "pvp";
	public static final String K_PLAYERS = "players";
	public static final String K_SEQ = "seq";
	public static final String K_GAME = "game";
	public static final String K_LEVEL = "level";
	public static final String K_DEPTH = "depth";
	public static final String K_BRANCH = "branch";
	public static final String K_HERO = "hero";
	public static final String K_TEXT = "text";
	public static final String K_KIND = "kind";
	public static final String K_CELL = "cell";
	public static final String K_INDEX = "index";
	public static final String K_ACTION = "action";

	// 增量字段
	public static final String K_CHARS = "chars";
	public static final String K_REMOVED = "removed";
	public static final String K_HEAPS = "heaps";
	public static final String K_MAP = "map";
	public static final String K_NEW = "new";
	public static final String K_HERO_ID = "hid";

	private Protocol() {
	}

	public static byte[] encode( Bundle bundle ) {
		return bundle.toString().getBytes( StandardCharsets.UTF_8 );
	}

	public static Bundle decode( byte[] payload ) throws IOException {
		return Bundle.read( new ByteArrayInputStream( payload ) );
	}

	public static void write( OutputStream stream, byte type, Bundle body ) throws IOException {
		byte[] payload = encode( body );
		DataOutputStream out = new DataOutputStream( stream );
		out.writeInt( payload.length + 1 );
		out.writeByte( type );
		out.write( payload );
		out.flush();
	}

	public static Frame read( InputStream stream ) throws IOException {
		DataInputStream in = new DataInputStream( stream );
		int length;
		try {
			length = in.readInt();
		} catch ( java.io.EOFException e ) {
			return null;
		}
		if (length <= 0 || length > MAX_FRAME) {
			throw new IOException( "bad frame length " + length );
		}
		byte type = in.readByte();
		byte[] payload = new byte[length - 1];
		in.readFully( payload );
		return new Frame( type, payload );
	}

	public static final class Frame {
		public final byte type;
		public final byte[] payload;

		Frame( byte type, byte[] payload ) {
			this.type = type;
			this.payload = payload;
		}
	}
}
