package org.mastodon.ctc.baseline;

import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>CTCMastodon")
public class AutonomousFullTrackerPlugin implements Command {
	@Parameter
	Context ctx;

	private static final float NOT_TOUCHED_ZTOXRATIO = -0.369f;

	@Parameter(description = "Either a path to .mastodon project file, or a template path such as folder/mask%04d.tif")
	String path;
	@Parameter(required = false, description = "Considered only for input TIFF series.")
	float ztoxratio = NOT_TOUCHED_ZTOXRATIO;
	@Parameter
	int from;
	@Parameter
	int till;

	@Parameter(required = false)
	String savepath = null;

	@Override
	public void run() {
		if (path.endsWith(".mastodon")) {
			//mastodon project:
			System.out.println("Btw, ignoring 'ztoxratio' as Mastodon project files come with own value for it.");
		} else {
			//tiff series:
			if (ztoxratio == NOT_TOUCHED_ZTOXRATIO) {
				System.out.println("Please, always provide the 'ztoxratio' for tiff series. Stopping now.");
				return;
			}
			if (ztoxratio <= 0.f) {
				System.out.println("Please, provide positive 'ztoxratio' for tiff series. Stopping now.");
				return;
			}
		}

		if (savepath == null || savepath.isEmpty() || savepath.startsWith("no"))
			AutonomousFullTracker.main(new String[]{path,String.valueOf(from),String.valueOf(till)}, ctx);
		else
			AutonomousFullTracker.main(new String[]{path,String.valueOf(from),String.valueOf(till), savepath}, ctx);
	}
}
