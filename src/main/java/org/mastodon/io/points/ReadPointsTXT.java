package org.mastodon.io.points;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.mastodon.mamut.MamutAppModel;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;

import net.imglib2.realtransform.AffineTransform3D;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;

@Plugin( type = Command.class, name = "Clouds points importer @ Mastodon" )
public class ReadPointsTXT extends DynamicCommand
{
	//visible params:
	@Parameter(
			label = "Choose TXT file to read the spots from points from it:",
			style = FileWidget.OPEN_STYLE)
	File selectedFile;

	@Parameter(
			label = "Should input points stretch over all timepoints:",
			initializer = "fillerOptionEnabler",
			callback = "fillerOptionEnabler")
	boolean fillAllTimepoints = false;

	@Parameter(
			label = "Or (if not ticked above), add them into this one timepoint:",
			callback = "checkTimeSpan")
	int useThisTimepoint = 0;

	private void fillerOptionEnabler() {
		//makes sure that the option is always disabled when four-column input is used
		if (fourthColumnIsTime) {
			fillAllTimepoints = false;
			useThisTimepoint = 0;
			//this, in fact, makes this gui item not to be displayed
			this.resolveInput("fillAllTimepoints");
			this.resolveInput("useThisTimepoint");
		}
	}

	private void checkTimeSpan() {
		useThisTimepoint = Math.max( appModel.getMinTimepoint(),
				Math.min(useThisTimepoint, appModel.getMaxTimepoint()) );
	}

	//not visible params:
	@Parameter(persist = false)
	boolean fourthColumnIsTime;

	@Parameter(persist = false)
	MamutAppModel appModel;

	@Override
	public void run() {
		importPoints();
	}

	public void importThreeColumnPoints() {
		fourthColumnIsTime = false;
		importPoints();
	}

	public void importFourColumnPoints() {
		fourthColumnIsTime = true;
		importPoints();
	}

	private void importPoints()
	{
		if (selectedFile == null) return;

		//check we can open the file; and complain if not
		if (!selectedFile.canRead())
			throw new IllegalArgumentException("Cannot read the selected file: "+selectedFile.getAbsolutePath());

		//-------------------------------------------------
		//scanning params:
		final String delim = "\t";
		final double spotRadius = 10;
		//-------------------------------------------------

		//define the spot size/radius
		final double[][] cov = new double[3][3];
		cov[0][0] = spotRadius*spotRadius;
		cov[1][1] = spotRadius*spotRadius;
		cov[2][2] = spotRadius*spotRadius;

		final Model model = appModel.getModel();
		final ModelGraph graph = model.getGraph();
		Spot spot = graph.vertices().createRef();
		final Spot oSpot = graph.vertices().createRef();
		final Link linkRef = graph.edgeRef();

		final AffineTransform3D transform = new AffineTransform3D();
		appModel.getSharedBdvData().getSources().get(0).getSpimSource().getSourceTransform(0,0, transform);

		final int timeF = appModel.getMinTimepoint();
		final int timeT = appModel.getMaxTimepoint();

		final double[] coords = new double[3];
		int time;

		final ReentrantReadWriteLock lock = graph.getLock();
		lock.writeLock().lock();

		try ( Scanner s = new Scanner(new BufferedReader(new FileReader(selectedFile.getAbsolutePath()))) ) {
			while (s.hasNext())
			{
				//read and prepare the spot spatial coordinate
				s.useDelimiter(delim);
				coords[0] = Float.parseFloat(s.next());
				coords[1] = Float.parseFloat(s.next());
				if (!fourthColumnIsTime) s.reset();
				coords[2] = Float.parseFloat(s.next());
				transform.apply(coords,coords);

				if (fourthColumnIsTime)
				{
					//add to the parsed-out time
					s.reset();
					time = Integer.parseInt(s.next());
					graph.addVertex( spot ).init( time, coords, cov );
				}
				else
				{
					if (fillAllTimepoints) {
						//spread this one over all time points available, and connect them with edges
						spot = graph.addVertex( spot ).init( timeF, coords, cov );
						oSpot.refTo(spot);
						for (int t = timeF+1; t <= timeT; ++t)
						{
							spot = graph.addVertex( spot ).init( t, coords, cov );
							graph.addEdge( oSpot, spot, linkRef ).init();
							oSpot.refTo(spot);
						}
					} else {
						//create this one as a solist in the current time point
						graph.addVertex( spot ).init( useThisTimepoint, coords, cov );
					}
				}
			}
		} catch (IOException e) {
			//report the original error message further
			e.printStackTrace();
		} finally {
			lock.writeLock().unlock();
		}

		graph.vertices().releaseRef(spot);
		graph.vertices().releaseRef(oSpot);
		graph.releaseRef(linkRef);
		model.getGraph().notifyGraphChanged();

		//this.context().getService(LogService.class).log().info("Loaded file: "+selectedFile.getAbsolutePath());
		System.out.print("Loaded file: "+selectedFile.getAbsolutePath());
		if (fourthColumnIsTime) {
			System.out.println(" with explicit time points (four-columns).");
		} else if (fillAllTimepoints) {
			System.out.println(" and placed the same spots into all time points.");
		} else {
			System.out.println(" and placed the spots into time point "+useThisTimepoint+".");
		}
	}
}
