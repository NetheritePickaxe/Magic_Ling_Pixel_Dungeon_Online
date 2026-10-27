package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.watabou.noosa.Camera;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;

/**
 * 联机专用英雄外观。
 *
 * HeroSprite 的构造与 updateArmor 全部绑死全局 Dungeon.hero，直接复用会把房主的
 * 职业/装备外观画到别人身上；而游戏里又有大量 ((HeroSprite)hero.sprite) 强转，
 * 所以这里继承 HeroSprite，用"构造前临时替换 Dungeon.hero"的方式拿到正确的皮肤，
 * 之后再把 updateArmor 重定向到自己的角色上。
 *
 * 另外把 HeroSprite 里"把镜头拉向自己"的副作用改回本地英雄，避免远端玩家一动镜头就跑。
 */
public class LanHeroSprite extends HeroSprite {

	/** 构造期间用来恢复镜头归属的本地英雄 sprite */
	private static CharSprite localHeroSprite;

	private static final int FRAME_WIDTH = 12;
	private static final int FRAME_HEIGHT = 15;

	public static LanHeroSprite create( Hero hero ) {
		Hero prevHero = Dungeon.hero;
		CharSprite prevSprite = localHeroSprite;

		localHeroSprite = prevHero != null ? prevHero.sprite : null;
		Dungeon.hero = hero;
		try {
			return new LanHeroSprite();
		} finally {
			Dungeon.hero = prevHero;
			CharSprite restore = localHeroSprite;
			localHeroSprite = prevSprite;
			if (restore != null && restore != prevSprite) {
				// 构造过程里可能改过 follow 目标
				Camera.main.panFollow( restore, 5f );
			}
		}
	}

	private LanHeroSprite() {
		super();
	}

	// ---------------------------------------------------------------- 外观

	@Override
	public void updateArmor() {
		updateArmor( ch instanceof Hero ? ((Hero) ch).tier() : 0 );
	}

	public void updateArmor( int tier ) {
		TextureFilm film = new TextureFilm( tiers(), tier, FRAME_WIDTH, FRAME_HEIGHT );

		idle = new Animation( 1, true );
		idle.frames( film, 0, 0, 0, 1, 0, 0, 1, 1 );

		run = new Animation( RUN_FRAMERATE, true );
		run.frames( film, 2, 3, 4, 5, 6, 7 );

		die = new Animation( 8, false );
		die.frames( film, 8, 9, 10, 11, 12, 12 );

		attack = new Animation( 15, false );
		attack.frames( film, 13, 14, 15, 0 );

		zap = attack.clone();

		operate = new Animation( 8, false );
		operate.frames( film, 16, 17, 16, 17 );

		fly = new Animation( 1, true );
		fly.frames( film, 18 );

		read = new Animation( 20, false );
		read.frames( film, 19, 20, 20, 20, 20, 20, 20, 20, 20, 19 );

		if (ch != null && ch.isAlive()) {
			idle();
		}
	}

	@Override
	public void bloodBurstA( PointF from, int damage ) {
		//与 HeroSprite 一致：不对人形角色显示血液特效
	}

	// ---------------------------------------------------------------- 镜头

	private void restoreCamera() {
		CharSprite local = localHeroSprite;
		if (local == null && Dungeon.hero != null) local = Dungeon.hero.sprite;
		if (local != null) Camera.main.panFollow( local, 5f );
	}

	@Override
	public void place( int p ) {
		super.place( p );
		restoreCamera();
	}

	@Override
	public void move( int from, int to ) {
		super.move( from, to );
		restoreCamera();
	}

	@Override
	public void jump( int from, int to, float height, float duration, Callback callback ) {
		super.jump( from, to, height, duration, callback );
		restoreCamera();
	}
}
