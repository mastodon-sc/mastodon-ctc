package org.mastodon.io.points;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.mastodon.mamut.MamutAppModel;
import org.mastodon.mamut.model.Spot;
import org.mastodon.spatial.SpatioTemporalIndex;

import net.imglib2.realtransform.AffineTransform3D;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;

@Plugin( type = Command.class, name = "Clouds points exporter @ Mastodon" )
public class WritePointsFourColumnTXT implements Command
{
	@Parameter(
			label = "Choose TXT file to write the spots as points:",
			style = FileWidget.SAVE_STYLE)
	File selectedFile;

	@Parameter(
			label = "Exporting from this time point:",
			callback = "timepointsCheck")
	int timeF = 0;

	@Parameter(
			label = "Exporting until this time point:",
			callback = "timepointsCheck")
	int timeT = 1;

	private void timepointsCheck() {
		timeF = Math.max( appModel.getMinTimepoint(),
				Math.min(timeF, appModel.getMaxTimepoint()) );
		timeT = Math.max( appModel.getMinTimepoint(),
				Math.min(timeT, appModel.getMaxTimepoint()) );

		if (timeT < timeF) timeT = timeF;
	}

	@Parameter(persist = false)
	MamutAppModel appModel;

	@Override
	public void run()
	{
		if (selectedFile == null) return;

		//-------------------------------------------------
		//writing params:
		final String delim = "\t";
		//-------------------------------------------------

		AffineTransform3D transform = new AffineTransform3D();
		appModel.getSharedBdvData().getSources().get(0).getSpimSource().getSourceTransform(0,0, transform);
		transform = transform.inverse();
		final double[] coords = new double[3];

		final SpatioTemporalIndex< Spot > spots = appModel.getModel().getSpatioTemporalIndex();
		BufferedWriter f;

		try
		{
			f = new BufferedWriter( new FileWriter(selectedFile.getAbsolutePath()) );

			for (int t = timeF; t <= timeT; ++t)
			for (final Spot s : spots.getSpatialIndex(t))
			{
				//convert spot's coordinate into underlying image coordinate system
				s.localize(coords);
				transform.apply(coords,coords);

				f.write(coords[0]+delim
				       +coords[1]+delim
				       +coords[2]+delim
				       +t);
				f.newLine();
			}
			f.close();
		}
		catch (IOException e) {
			//report the original error message further
			e.printStackTrace();
		}

		System.out.println("Wrote file: "+selectedFile.getAbsolutePath());
	}
}
