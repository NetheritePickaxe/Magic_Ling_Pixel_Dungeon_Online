package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;

/**
 * 主机侧代表"远端玩家"的英雄实体。
 *
 * 时间线规则（MVP）：
 *  - 空闲时把自己 diactivate()（时间推到 Float.MAX_VALUE），这样它永远不会被
 *    Actor 选中，也就不会成为"世界暂停点"。
 *  - 收到命令后 timeToNow() 唤醒，并临时提高 actPriority，保证在与主机英雄
 *    同一时间点时优先被选中（否则 Actor.process 的平局判定会让主机英雄先返回 false
 *    从而死锁——current 停在主机英雄以外时，主机英雄的 next() 无法清空它）。
 *  - act() 永远返回 true：真正让世界停下来的是主机英雄（单机英雄）。
 */
public class HostLanHero extends Hero {

	public int playerId;

	private volatile int pendingCell = -1;
	private volatile int pendingRest = 0; //0=无 1=等待 2=休息
	//物品使用等不留下 curAction 的命令也要让局部回合制识别为"有待办行动"
	private volatile boolean pendingWake = false;
	//wake() 时用的是当前活动区域的时钟；局部回合制切回自己的区域后需要重新对齐一次
	private volatile boolean wakeTimeSync = false;

	/** 局部回合制用：是否有待处理/进行中的行动（比 idle() 多覆盖一次性唤醒） */
	public boolean hasPendingCommand() {
		return pendingWake || !idle();
	}

	/** 是否是"刚收到格子/休息命令、尚未进入 act"的唤醒 */
	public boolean needsWakeTimeSync() {
		return wakeTimeSync;
	}

	public HostLanHero() {
		super();
	}

	// ---------------------------------------------------------------- 命令注入（渲染线程）

	/** 队列一个格子行动 */
	public boolean queueCell( int cell ) {
		if (!idle()) return false;
		pendingCell = cell;
		wake();
		return true;
	}

	/** 队列等待/休息 */
	public boolean queueRest( boolean fullRest ) {
		if (!idle()) return false;
		pendingRest = fullRest ? 2 : 1;
		wake();
		return true;
	}

	public boolean idle() {
		return curAction == null && pendingCell < 0 && pendingRest == 0;
	}

	/**
	 * 执行一次物品动作。物品执行本身是即时的（喝药/读卷/装备），
	 * 之后把英雄唤醒一帧让世界推进一次。
	 */
	public boolean useItem( Item item, String action ) {
		if (!idle()) return false;
		pendingWake = true;
		resting = false;
		actPriority = HERO_PRIO + 1;
		try {
			item.execute( this, action );
		} catch (Exception e) {
			Lan.wakeActorThread();
			return false;
		}
		Lan.wakeActorThread();
		return true;
	}

	// ---------------------------------------------------------------- 时间线

	private void wake() {
		pendingWake = true;
		wakeTimeSync = true;
		resting = false;
		timeToNow();
		actPriority = HERO_PRIO + 1;
		Lan.wakeActorThread();
	}

	private void park() {
		pendingCell = -1;
		pendingRest = 0;
		resting = false;
		actPriority = HERO_PRIO - 1;
		diactivate();
	}

	/** 生成/换层后立即挂起，避免它被 Actor 选中 */
	public void parkNow() {
		park();
	}

	/**
	 * 行动结束后的挂起方式：
	 *  - 共享时间线：park 到 MAX，暂停点恒为主机英雄；
	 *  - 局部回合制：若本活动区域已没有其他待办玩家，则在自己身上暂停（保留 time，
	 *    并 next() 清掉 current，避免主机输入唤不醒 Actor 线程）；否则仍 park 让出调度。
	 */
	private boolean suspend() {
		if (Lan.isHost() && Lan.localTurns && Lan.host().localTurns().pauseHere( this )) {
			pendingCell = -1;
			pendingRest = 0;
			resting = false;
			actPriority = HERO_PRIO - 1;
			next();
			return false;
		}
		park();
		return true;
	}

	// ---------------------------------------------------------------- Actor

	@Override
	public boolean act() {
		int cell = pendingCell;
		int restMode = pendingRest;
		pendingCell = -1;
		pendingRest = 0;
		pendingWake = false;
		wakeTimeSync = false;

		//没有任何待处理命令时空转一次：直接挂起，绝不走 Hero.act() 的空闲分支
		//（那个分支会调用 ready() -> GameScene.ready()，从而重置房主正在进行的选格操作）
		if (restMode == 0 && cell < 0 && curAction == null) {
			return suspend();
		}

		if (restMode > 0) {
			rest( restMode == 2 );
			return suspend();
		}

		if (cell >= 0) {
			if (!handle( cell )) {
				return suspend();
			}
		}

		boolean acted = super.act();

		if (!acted) {
			return suspend();
		}
		//共享时间线下永不作为暂停点，世界暂停始终由主机英雄负责
		return true;
	}

	/**
	 * 远端玩家死亡：不触发 Hero.reallyDie（那会直接结束主机的整局游戏），
	 * 只做表现与移出调度。MVP 里死亡 = 旁观，等后续版本再补复活。
	 */
	@Override
	public void die( Object cause ) {
		curAction = null;
		pendingCell = -1;
		pendingRest = 0;
		pendingWake = false;
		HP = 0;
		if (sprite != null) sprite.die();
		com.shatteredpixel.shatteredpixeldungeon.actors.Actor.remove( this );
		if (Lan.isHost()) Lan.host().onPlayerDied( this );
	}

	/** 把自己重新挂回本地 Actor 调度（换层后需要） */
	public void attach() {
		com.shatteredpixel.shatteredpixeldungeon.actors.Actor.add( this );
	}

	public void detach() {
		com.shatteredpixel.shatteredpixeldungeon.actors.Actor.remove( this );
		if (sprite != null) {
			sprite.killAndErase();
			sprite = null;
		}
	}

	public int depth() {
		return Dungeon.depth;
	}
}
