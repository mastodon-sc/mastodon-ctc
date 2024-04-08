package org.mastodon.ctc.baseline;

import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>CTCMastodon")
public class AutonomousFullTrackerPlugin implements Command {
	@Parameter
	Context ctx;

	@Parameter
	String path;
	@Parameter
	int from;
	@Parameter
	int till;

	@Parameter(required = false)
	String savepath = null;

	@Override
	public void run() {
		if (savepath == null || savepath.isEmpty() || savepath.startsWith("no"))
			AutonomousFullTracker.main(new String[]{path,String.valueOf(from),String.valueOf(till)}, ctx);
		else
			AutonomousFullTracker.main(new String[]{path,String.valueOf(from),String.valueOf(till), savepath}, ctx);
	}
}
