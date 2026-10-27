/*
 * Magic Ling Pixel Dungeon
 *
 * 联机桥接层。
 *
 * 这是 core 与 :lan 模块之间唯一的接触面：
 * core 只在这里暴露少量钩子，默认没有任何实现（纯单机，行为与原来完全一致）；
 * 桌面端启动时由 DesktopLauncher 装配一次 LanBridge，联机逻辑全部在 :lan 内。
 *
 * 约定：所有钩子都必须是轻量的，不允许在游戏线程里做网络 IO 或阻塞操作。
 */

package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;

import java.util.HashSet;

public abstract class LanHooks {

	private static volatile LanHooks instance;

	public static void install( LanHooks hooks ) {
		instance = hooks;
	}

	public static LanHooks get() {
		return instance;
	}

	public static boolean installed() {
		return instance != null;
	}

	/** 游戏内菜单追加按钮（WndGame 里调用，配合 WndGame.addButton 的位置计算） */
	public interface MenuButtonAdder {
		void add( RedButton button );
	}

	// ---------------------------------------------------------------- 场景生命周期

	/** GameScene.create() 结束时调用 */
	public void onSceneCreated( GameScene scene ) {
	}

	/** GameScene.destroy() 开始时调用 */
	public void onSceneDestroyed( GameScene scene ) {
	}

	/** GameScene.update() 每帧（渲染线程）调用 */
	public void onFrame( GameScene scene ) {
	}

	// ---------------------------------------------------------------- 输入拦截

	/** 返回 true 表示格子行动已由联机接管，本地不再执行 hero.handle() */
	public boolean interceptCellSelect( int cell ) {
		return false;
	}

	/** 返回 true 表示休息/等待已由联机接管 */
	public boolean interceptHeroRest( Hero hero, boolean fullRest ) {
		return false;
	}

	/** 返回 true 表示物品使用已由联机接管 */
	public boolean interceptItemExecute( Hero hero, Item item, String action ) {
		return false;
	}

	// ---------------------------------------------------------------- 逻辑线程

	/** 房客端返回 true：不创建本地 Actor 逻辑线程，世界状态完全由主机事件驱动 */
	public boolean suppressActorThread() {
		return false;
	}

	// ---------------------------------------------------------------- 回合调度

	/** Actor.process 每轮选取行动者之前调用（局部回合制在这里切换活动区域与时钟） */
	public void beforeActorSelection() {
	}

	/** 返回 false 表示该 actor 本轮不参与调度（局部回合制下范围外 actor 被冻结） */
	public boolean isActorSchedulable( Actor actor ) {
		return true;
	}

	/** Actor.fixTime() 把所有时间轴整体回拨 offset 后调用，便于联机侧同步自己的时钟 */
	public void onActorTimeFixed( float offset ) {
	}

	// ---------------------------------------------------------------- 怪物目标

	/** 为怪物 AI 追加可攻击的角色（多人模式下其它玩家也是目标） */
	public void addExtraTargets( HashSet<Char> targets, Mob mob ) {
	}

	// ---------------------------------------------------------------- UI 入口

	/** 创建标题界面的"联机"入口按钮；返回 null 表示不提供。按钮的摆放由 TitleScene 统一负责 */
	public StyledButton createTitleButton() {
		return null;
	}

	/** 游戏内菜单追加"对局域网开放"入口 */
	public void addGameMenuButtons( MenuButtonAdder adder ) {
	}

}
