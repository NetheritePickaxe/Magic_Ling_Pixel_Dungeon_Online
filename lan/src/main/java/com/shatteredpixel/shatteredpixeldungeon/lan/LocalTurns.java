package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 局部回合制（仅主机）：活动玩家原版视野内的 actor 参与回合推进，
 * 视野外 actor 全部冻结；远处的其他玩家因此不受这边回合推进的影响。
 *
 * 实现要点：
 *  - 每个玩家有自己的局部时钟，活动玩家推进时 Actor.now 就是该时钟；
 *  - 同一时刻只推进活动玩家 FOV 内的 actor，视野相接的其他玩家自然共享这段时间；
 *  - 切换活动玩家/进出范围时，用 shiftTime 平移时间轴，保持 cooldown、绝不补跑冻结期间的回合；
 *  - 非定位 actor（blob、楼层 respawner 等）跟随当前活动区域的时间，不做区域拆分。
 */
final class LocalTurns {

	private final Map<Integer, Float> clocks = new HashMap<>();
	private final Set<Actor> active = Collections.newSetFromMap( new IdentityHashMap<Actor, Boolean>() );
	private final IdentityHashMap<Actor, Float> frozen = new IdentityHashMap<>();
	private boolean[] activeFov = new boolean[0];

	//0 = 房主，其余为 HostPlayer.id
	private int activePlayer = 0;
	private boolean initialized;

	void reset() {
		active.clear();
		frozen.clear();
		clocks.clear();
		activeFov = new boolean[0];
		activePlayer = 0;
		initialized = false;
	}

	/** 关闭局部回合制：把冻结 actor 对齐回当前时钟，避免切回共享模式后补跑 */
	void disable() {
		if (initialized) {
			float now = Actor.now;
			for (Map.Entry<Actor, Float> e : frozen.entrySet()) {
				e.getKey().shiftTime( now - e.getValue() );
			}
		}
		reset();
	}

	/** Actor.fixTime 整体回拨时间轴后，同步联机侧保存的时钟 */
	void onTimeFixed( float offset ) {
		for (Map.Entry<Actor, Float> e : frozen.entrySet()) {
			e.setValue( e.getValue() - offset );
		}
		for (Map.Entry<Integer, Float> e : clocks.entrySet()) {
			e.setValue( e.getValue() - offset );
		}
	}

	boolean schedulable( Actor actor ) {
		return !initialized || active.contains( actor );
	}

	void beforeSelection() {
		if (!initialized) {
			initialized = true;
			activePlayer = 0;
			clocks.put( 0, Actor.now );
		}

		float oldNow = Actor.now;

		//有待办行动的玩家驱动活动区域；当前玩家还在行动时不切换，避免一个行动被切成两段
		int next = findPendingPlayer();
		if (next >= 0 && next != activePlayer) {
			clocks.put( activePlayer, oldNow );
			activePlayer = next;
			Float clock = clocks.get( next );
			Actor.now = clock != null ? clock : oldNow;
		}

		rebuildActive( oldNow, Actor.now );

		//远端玩家 wake() 时对齐的是旧活动区域的时钟，切回它自己的区域后要重新对齐，
		//否则它可能要等本区域"追上"另一个区域的时间才能行动
		if (activePlayer > 0) {
			HostLanHero hero = heroOf( activePlayer );
			if (hero != null && hero.needsWakeTimeSync()) hero.timeToNow();
		}

		syncPlayerClocks();
	}

	/** HostLanHero 空闲时询问：是否应在自己身上暂停世界（而不是挂起到 MAX） */
	boolean pauseHere( HostLanHero self ) {
		if (!initialized || !active.contains( self )) return false;

		if (Dungeon.hero != null && active.contains( Dungeon.hero ) && hasPending( 0 )) return false;
		for (HostPlayer p : Lan.host().playersView()) {
			HostLanHero h = p.hero;
			if (h == null || h == self || !active.contains( h )) continue;
			if (h.hasPendingCommand()) return false;
		}
		return true;
	}

	// ---------------------------------------------------------------- 内部

	private int findPendingPlayer() {
		if (hasPending( activePlayer )) return activePlayer;
		if (hasPending( 0 )) return 0;
		for (HostPlayer p : Lan.host().playersView()) {
			if (p.hero != null && hasPending( p.id )) return p.id;
		}
		return -1;
	}

	private boolean hasPending( int playerId ) {
		if (playerId == 0) {
			Hero h = Dungeon.hero;
			return h != null && (!h.ready || h.curAction != null || h.resting);
		}
		HostLanHero h = heroOf( playerId );
		return h != null && h.hasPendingCommand();
	}

	private HostLanHero heroOf( int playerId ) {
		for (HostPlayer p : Lan.host().playersView()) {
			if (p.id == playerId) return p.hero;
		}
		return null;
	}

	private void rebuildActive( float oldNow, float newNow ) {
		Hero focus = activePlayer == 0 ? Dungeon.hero : heroOf( activePlayer );
		if (focus == null) focus = Dungeon.hero;
		if (focus == null) return;

		updateActiveFov( focus );
		Set<Actor> all = Actor.all();
		Set<Actor> next = Collections.newSetFromMap( new IdentityHashMap<Actor, Boolean>() );
		for (Actor a : all) {
			if (inArea( a, focus )) next.add( a );
		}

		for (Actor a : active) {
			if (!next.contains( a )) {
				//离开范围：冻结在旧活动时钟
				frozen.put( a, oldNow );
			} else {
				//仍在范围：活动玩家切换时对齐到新时钟
				a.shiftTime( newNow - oldNow );
			}
		}
		for (Actor a : next) {
			if (active.contains( a )) continue;
			Float frozenAt = frozen.remove( a );
			//恢复冻结 actor 时保留剩余冷却，不补跑；新 actor 按旧活动时钟对齐
			a.shiftTime( newNow - (frozenAt != null ? frozenAt : oldNow) );
		}

		frozen.keySet().retainAll( all );
		active.clear();
		active.addAll( next );
	}

	private void syncPlayerClocks() {
		if (Dungeon.hero != null && active.contains( Dungeon.hero )) {
			clocks.put( 0, Actor.now );
		}
		for (HostPlayer p : Lan.host().playersView()) {
			if (p.hero != null && active.contains( p.hero )) {
				clocks.put( p.id, Actor.now );
			}
		}
	}

	private void updateActiveFov( Hero focus ) {
		if (activeFov.length != Dungeon.level.length()) {
			activeFov = new boolean[Dungeon.level.length()];
		}
		Hero previous = Dungeon.hero;
		try {
			//updateFieldOfView 的英雄感知分支以 Dungeon.hero 判断，远端玩家也按原版英雄规则计算
			Dungeon.hero = focus;
			Dungeon.level.updateFieldOfView( focus, activeFov );
		} finally {
			Dungeon.hero = previous;
		}
	}

	private boolean inArea( Actor actor, Hero focus ) {
		if (actor == focus) return true;
		if (actor instanceof Buff) {
			Char target = ((Buff) actor).target;
			return target != null && visible( target.pos );
		}
		if (actor instanceof Char) {
			return visible( ((Char) actor).pos );
		}
		//blob、respawner 等非定位 actor 跟随当前活动区域
		return true;
	}

	private boolean visible( int pos ) {
		return pos >= 0 && pos < activeFov.length && activeFov[pos];
	}
}
