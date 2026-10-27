package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.badlogic.gdx.Gdx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 手动添加的房主地址，持久化到游戏本地目录，供标题界面「多人游戏」快捷选择。 */
final class LanHosts {

	private static final String FILE = "lan_hosts.txt";
	private static final int MAX = 8;

	static String last() {
		List<String> r = recent();
		return r.isEmpty() ? "" : r.get( 0 );
	}

	static List<String> recent() {
		List<String> list = new ArrayList<>();
		try {
			com.badlogic.gdx.files.FileHandle f = Gdx.files.local( FILE );
			if (f.exists()) {
				for (String line : f.readString().split( "\n" )) {
					String s = line.trim();
					if (!s.isEmpty()) list.add( s );
				}
			}
		} catch (Exception ignored) {
		}
		return list;
	}

	/** 记住一个地址（去重、置顶、最多保留 MAX 条） */
	static void remember( String host ) {
		if (host == null) return;
		host = host.trim();
		if (host.isEmpty()) return;
		Set<String> set = new LinkedHashSet<>();
		set.add( host );
		set.addAll( recent() );
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (String s : set) {
			if (i++ >= MAX) break;
			sb.append( s ).append( '\n' );
		}
		try {
			Gdx.files.local( FILE ).writeString( sb.toString(), false );
		} catch (Exception ignored) {
		}
	}
}
