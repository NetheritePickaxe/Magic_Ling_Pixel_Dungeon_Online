package com.shatteredpixel.shatteredpixeldungeon.lan;

/** 联机日志。统一输出到 stderr，方便排查状态分叉。 */
final class Log {

	private Log() {
	}

	static void warn( String text ) {
		try {
			System.err.println( "[LAN] " + text );
		} catch (Exception ignored) {
		}
	}

	static void error( Throwable t ) {
		try {
			System.err.println( "[LAN] " + t );
			t.printStackTrace();
		} catch (Exception ignored) {
		}
	}
}
