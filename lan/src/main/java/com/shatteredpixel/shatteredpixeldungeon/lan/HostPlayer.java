package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

/** 主机侧的一个玩家会话 */
final class HostPlayer {

	final int id;
	final String name;
	final String token;
	final HeroClass heroClass;
	final LanConnection conn;

	volatile HostLanHero hero;

	HostPlayer( int id, String name, String token, HeroClass heroClass, LanConnection conn ) {
		this.id = id;
		this.name = name;
		this.token = token;
		this.heroClass = heroClass;
		this.conn = conn;
	}

	String uid() {
		return "p" + id;
	}
}
