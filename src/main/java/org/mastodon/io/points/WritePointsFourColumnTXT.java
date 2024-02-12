/*-
 * #%L
 * mastodon-ctc
 * %%
 * Copyright (C) 2019 - 2024 Vladimir Ulman
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.mastodon.io.points;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.mastodon.mamut.ProjectModel;
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
		timeF = Math.max( projectModel.getMinTimepoint(),
				Math.min(timeF, projectModel.getMaxTimepoint()) );
		timeT = Math.max( projectModel.getMinTimepoint(),
				Math.min(timeT, projectModel.getMaxTimepoint()) );

		if (timeT < timeF) timeT = timeF;
	}

	@Parameter(persist = false)
	ProjectModel projectModel;

	@Override
	public void run()
	{
		if (selectedFile == null) return;

		//-------------------------------------------------
		//writing params:
		final String delim = "\t";
		//-------------------------------------------------

		AffineTransform3D transform = new AffineTransform3D();
		projectModel.getSharedBdvData().getSources().get(0).getSpimSource().getSourceTransform(0,0, transform);
		transform = transform.inverse();
		final double[] coords = new double[3];

		final SpatioTemporalIndex< Spot > spots = projectModel.getModel().getSpatioTemporalIndex();
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
