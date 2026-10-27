package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;

/** 窗口展示：游戏内走 GameScene.show，标题界面走当前场景。 */
final class Ui {

	private Ui() {
	}

	static void show( Window window ) {
		if (GameScene.scene != null) {
			GameScene.show( window );
			return;
		}
		Scene scene = Game.scene();
		if (scene != null) scene.addToFront( window );
	}

	static void postShow( final Window window ) {
		Game.runOnRenderThread( () -> show( window ) );
	}

	static void error( final String message ) {
		postShow( new com.shatteredpixel.shatteredpixeldungeon.windows.WndError( message ) );
	}
}
