package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.custom.utils.NetIcons;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;

import java.util.HashSet;

/**
 * core 的 LanHooks 在桌面端的唯一实现。
 *
 * DesktopLauncher 启动时装配一次：LanHooks.install(new LanBridge())。
 */
public final class LanBridge extends LanHooks {

	//房客端允许直接使用的物品动作（不需要在主机上二次选格子的那些）
	private static final HashSet<String> CLIENT_ITEM_ACTIONS = new HashSet<>();

	static {
		CLIENT_ITEM_ACTIONS.add( "DRINK" );
		CLIENT_ITEM_ACTIONS.add( "READ" );
		CLIENT_ITEM_ACTIONS.add( "EAT" );
		CLIENT_ITEM_ACTIONS.add( "EQUIP" );
		CLIENT_ITEM_ACTIONS.add( "UNEQUIP" );
		CLIENT_ITEM_ACTIONS.add( "DETACH" );
		CLIENT_ITEM_ACTIONS.add( "DROP" );
	}

	// ---------------------------------------------------------------- 场景

	@Override
	public void onSceneCreated( GameScene scene ) {
		if (Lan.isHost()) {
			Lan.host().onSceneCreated();
		} else if (Lan.isClient()) {
			ClientSession c = Lan.client();
			if (c != null) c.onSceneCreated();
		}
	}

	@Override
	public void onSceneDestroyed( GameScene scene ) {
		if (Lan.isClient()) {
			ClientSession c = Lan.client();
			if (c != null) c.onSceneDestroyed();
		}
	}

	@Override
	public void onFrame( GameScene scene ) {
		if (Lan.isHost()) {
			Lan.host().onFrame();
		} else if (Lan.isClient()) {
			ClientSession c = Lan.client();
			if (c != null) c.onFrame();
		}
	}

	// ---------------------------------------------------------------- 输入

	@Override
	public boolean interceptCellSelect( int cell ) {
		if (Lan.isClient()) {
			ClientSession c = Lan.client();
			return c != null && c.sendCell( cell );
		}
		return false;
	}

	@Override
	public boolean interceptHeroRest( Hero hero, boolean fullRest ) {
		if (Lan.isClient() && hero == Dungeon.hero) {
			ClientSession c = Lan.client();
			return c != null && c.sendRest( fullRest );
		}
		return false;
	}

	@Override
	public boolean interceptItemExecute( Hero hero, Item item, String action ) {
		if (!Lan.isClient() || hero != Dungeon.hero) return false;

		ClientSession c = Lan.client();
		if (c == null) return false;

		if (CLIENT_ITEM_ACTIONS.contains( action )) {
			if (c.sendItem( item, action )) return true;
		}

		//需要主机二次选格子的动作（投掷/法杖/选择）暂不支持，交回本地会与主机状态分叉
		com.shatteredpixel.shatteredpixeldungeon.utils.GLog.w( "联机模式暂不支持该操作" );
		return true;
	}

	@Override
	public boolean suppressActorThread() {
		return Lan.isClient();
	}

	// ---------------------------------------------------------------- 回合调度

	@Override
	public void beforeActorSelection() {
		if (Lan.isHost() && Lan.localTurns) {
			Lan.host().localTurns().beforeSelection();
		}
	}

	@Override
	public boolean isActorSchedulable( Actor actor ) {
		if (Lan.isHost() && Lan.localTurns) {
			return Lan.host().localTurns().schedulable( actor );
		}
		return true;
	}

	@Override
	public void onActorTimeFixed( float offset ) {
		if (Lan.isHost()) {
			Lan.host().localTurns().onTimeFixed( offset );
		}
	}

	// ---------------------------------------------------------------- 怪物目标

	@Override
	public void addExtraTargets( HashSet<Char> targets, Mob mob ) {
		if (!Lan.isHost()) return;
		//只有敌对怪物才把其它玩家算作目标
		if (mob.alignment != Char.Alignment.ENEMY) return;
		if (mob.fieldOfView == null) return;

		for (HostPlayer p : Lan.host().playersView()) {
			Hero h = p.hero;
			if (h == null || !h.isAlive() || h.pos < 0 || h.pos >= mob.fieldOfView.length) continue;
			if (!mob.fieldOfView[h.pos]) continue;
			if (h.invisible > 0) continue;
			targets.add( h );
		}
	}

	// ---------------------------------------------------------------- UI

	@Override
	public StyledButton createTitleButton() {
		StyledButton btn = new StyledButton( Chrome.Type.GREY_BUTTON_TR, "多人游戏" ) {
			@Override
			protected void onClick() {
				ShatteredPixelDungeon.switchNoFade( LanScene.class );
			}
		};
		btn.icon( NetIcons.get( NetIcons.PLAYERS ) );
		return btn;
	}

	@Override
	public void addGameMenuButtons( MenuButtonAdder adder ) {
		RedButton btn = new RedButton( "对局域网开放" ) {
			@Override
			protected void onClick() {
				Ui.show( new WndLanHost() );
			}
		};
		btn.icon( Icons.get( Icons.ENTER ) );
		adder.add( btn );
	}
}
