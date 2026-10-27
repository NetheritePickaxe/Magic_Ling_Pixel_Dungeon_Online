package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.io.IOException;

/** 房客端快照落盘：把主机下发的 Bundle 写进房客内部槽位，交给 InterlevelScene 读档。 */
final class LanSnapshot {

	private LanSnapshot() {
	}

	static void writeLevel( int slot, int depth, int branch, Bundle levelBundle ) throws IOException {
		FileUtils.bundleToFile( GamesInProgress.depthFile( slot, depth, branch ), levelBundle );
	}

	static void writeGame( int slot, Bundle gameBundle ) throws IOException {
		FileUtils.bundleToFile( GamesInProgress.gameFile( slot ), gameBundle );
	}
}
