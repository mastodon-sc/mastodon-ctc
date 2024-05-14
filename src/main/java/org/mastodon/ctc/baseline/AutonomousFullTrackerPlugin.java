package org.mastodon.ctc.baseline;

import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;
import java.io.File;

@Plugin(type = Command.class, menuPath = "Plugins>Baseline Mastodon CTC tracking")
public class AutonomousFullTrackerPlugin implements Command {
	@Parameter
	Context ctx;

	private static final float INITIAL_WRONG_RATIO_VALUE = -1.0f;

	@Parameter(label = "Path to a folder with maskTTT.tif files (e.g. to 0x_ERR_SEG):", style = FileWidget.DIRECTORY_STYLE)
	File path;
	@Parameter(label = "Number of digits used in filenames:", min = "3", max = "4")
	int noOfDigits = 3;

	@Parameter(label = "How many times is a voxel larger in z-axis compared to x-axis:",
			  description = "Set 1.0 for isotropic images.")
	float ztoxratio = INITIAL_WRONG_RATIO_VALUE;

	@Parameter(label = "The first processed time point:", required = false, min = "0", stepSize = "1")
	int from = 0;
	@Parameter(label = "The last processed time point:", required = false, min = "-1", stepSize = "1",
			description = "Set to -1 to let the program auto-determine the last time point.")
	int till = -1;

	@Parameter(label = "Path to a folder (e.g. 0x_RES) to hold maskTTT.tif and res_track.txt files:", style = FileWidget.DIRECTORY_STYLE)
	File savepath;

	@Override
	public void run() {
		//tiff series:
		if (ztoxratio <= 0.f) {
			System.out.println("Please, always provide strictly-positive 'ztoxratio'. Stopping now.");
			return;
		}

		final String inFileTemplate = path.getAbsolutePath() + File.separator + "mask%0"+noOfDigits+"d.tif";
		final String outFileTemplate = savepath.getAbsolutePath() + File.separator + "mask%0"+noOfDigits+"d.tif";

		AutonomousFullTracker.main(new String[]{
				  inFileTemplate, String.valueOf(ztoxratio),
				  String.valueOf(from), String.valueOf(till),
				  outFileTemplate}, ctx);
	}
}
