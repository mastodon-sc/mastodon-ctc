package org.mastodon.ctc.baseline;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.imagej.ImageJ;
import org.mastodon.mamut.io.ProjectSaver;
import org.scijava.Context;
import mpicbg.spim.data.SpimDataException;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import org.mastodon.kdtree.IncrementalNearestNeighborSearch;
import org.mastodon.mamut.io.ProjectLoader;
import org.mastodon.mamut.io.project.MamutProject;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.views.bdv.MamutViewBdv;
import org.mastodon.mamut.views.trackscheme.MamutViewTrackScheme;
import org.mastodon.spatial.SpatialIndex;
import org.mastodon.tracking.linking.LinkingUtils;
import org.mastodon.tracking.mamut.linking.SpotLinkerOp;
import org.mastodon.tracking.mamut.linking.SparseLAPLinkerMamut;
import org.mastodon.tracking.mamut.trackmate.Settings;
import org.mastodon.tracking.mamut.trackmate.TrackMate;
import org.mastodon.ctc.util.ImgProviders;
import org.mastodon.mamut.ProjectModel;
import org.mastodon.views.bdv.SharedBigDataViewerData;

import static org.mastodon.tracking.detection.DetectorKeys.KEY_MAX_TIMEPOINT;
import static org.mastodon.tracking.detection.DetectorKeys.KEY_MIN_TIMEPOINT;
import static org.mastodon.tracking.linking.LinkerKeys.KEY_LINKING_MAX_DISTANCE;
import static org.mastodon.tracking.linking.LinkerKeys.KEY_ALLOW_GAP_CLOSING;
import static org.mastodon.tracking.linking.LinkerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP;
import static org.mastodon.tracking.linking.LinkerKeys.KEY_GAP_CLOSING_MAX_DISTANCE;
import static org.mastodon.tracking.linking.LinkerKeys.KEY_ALLOW_TRACK_SPLITTING;
import static org.mastodon.tracking.linking.LinkerKeys.KEY_SPLITTING_MAX_DISTANCE;

public class AutonomousFullTracker {

	static public void link(final ProjectModel projectModel,
	                        final double maxSearchDist,
	                        final int timeFrom,
	                        final int timeTill) {

		System.out.println("Tracking using search dist = "+maxSearchDist);

		final Class< ? extends SpotLinkerOp> linker = SparseLAPLinkerMamut.class;
		final Map< String, Object > linkerSettings = LinkingUtils.getDefaultLAPSettingsMap();
		linkerSettings.put( KEY_MIN_TIMEPOINT, timeFrom );
		linkerSettings.put( KEY_MAX_TIMEPOINT, timeTill );
		linkerSettings.put( KEY_LINKING_MAX_DISTANCE, maxSearchDist );
		linkerSettings.put( KEY_ALLOW_GAP_CLOSING, true );
		linkerSettings.put( KEY_GAP_CLOSING_MAX_FRAME_GAP, 2 );
		linkerSettings.put( KEY_GAP_CLOSING_MAX_DISTANCE, 2.0 * maxSearchDist );
		linkerSettings.put( KEY_ALLOW_TRACK_SPLITTING, true );
		linkerSettings.put( KEY_SPLITTING_MAX_DISTANCE, 1.5 * maxSearchDist );
		//
		final Settings settings = new Settings()
				.linker( linker )
				.linkerSettings( linkerSettings );

		final long start = System.currentTimeMillis();
		final TrackMate trackmate = new TrackMate(
				settings, projectModel.getModel(), projectModel.getSelectionModel() );
		trackmate.setContext( projectModel.getContext() );
		if ( !trackmate.execParticleLinking() )
		{
			System.out.println( "Tracking failed: " + trackmate.getErrorMessage() );
			return;
		}
		final long end = System.currentTimeMillis();
		System.out.println( String.format( "Tracking successful. Done in %.1f s.", ( end - start ) / 1000. ) );
	}


	static void updateMinMax(final double[] statVector, final double[] pos) {
		if (statVector[3] == 0) {
			//first update of the stats...
			//x
			statVector[4] = pos[0];
			statVector[7] = pos[0];
			//y
			statVector[5] = pos[1];
			statVector[8] = pos[1];
			//z
			statVector[6] = pos[2];
			statVector[9] = pos[2];
			return;
		}

		//x
		statVector[4] = Math.min(statVector[4],pos[0]);
		statVector[7] = Math.max(statVector[7],pos[0]);
		//y
		statVector[5] = Math.min(statVector[5],pos[1]);
		statVector[8] = Math.max(statVector[8],pos[1]);
		//z
		statVector[6] = Math.min(statVector[6],pos[2]);
		statVector[9] = Math.max(statVector[9],pos[2]);
	}

	static <T extends IntegerType<T>>
	void findAndSetSpots(final RandomAccessibleInterval<T> img,
	                     final double[] pxSizes,
	                     final int time,
	                     final ModelGraph graph, final Spot auxSpot) {

		final Map<Integer,double[]> geomStats = new HashMap<>(500);
		int label;

		final Cursor<T> c = Views.iterable(img).localizingCursor();
		final double[] pos = new double[3];

		//fill up the stats
		while (c.hasNext()) {
			label = c.next().getInteger();
			if (label > 0) {
				double[] stat = geomStats.computeIfAbsent(label, k -> new double[10]); //3+1+3+3
				c.localize(pos);
				updateMinMax(stat,pos);       //update the image coords!
				stat[0] += pos[0]*pxSizes[0]; //transform from image coords to Mastodon coords
				stat[1] += pos[1]*pxSizes[1];
				stat[2] += pos[2]*pxSizes[2];
				stat[3] += 1;
			}
		}

		//finish the stats and create spots
		for (double[] stat : geomStats.values()) {
			pos[0] = stat[0] / stat[3];
			pos[1] = stat[1] / stat[3];
			pos[2] = stat[2] / stat[3];
			graph.addVertex(auxSpot).init(time,pos,3);
		}
		System.out.println("TP "+time+" found "+geomStats.size()+" spots");
	}


	static public double detect(final ProjectModel projectModel,
	                            final ImgProviders.ImgProvider imageSrc,
	                            final int timeFrom,
	                            final int timeTill)
	{
		final ModelGraph graph = projectModel.getModel().getGraph();
		Spot spot = graph.vertexRef();

		Map<Integer,Integer> distances = new HashMap<>(500);
		for (int t = timeFrom; t <= timeTill; ++t) {
			findAndSetSpots((RandomAccessibleInterval)imageSrc.getImage(t),
					  imageSrc.getVoxelDimensions().dimensionsAsDoubleArray(),
					  t, graph,spot);

			//analyze mutual spots distances
			SpatialIndex<Spot> index = projectModel.getModel().getSpatioTemporalIndex().getSpatialIndex(t);
			IncrementalNearestNeighborSearch<Spot> search = index.getIncrementalNearestNeighborSearch();
			for (Spot s : index) {
				search.search(s);
				search.next();           //skip over itself
				if (search.hasNext()) {  //a potential first real neighbor?
					int dist = (int)Util.distance(search.next(),s);
					//System.out.println("dist = "+dist);
					distances.put(dist, distances.getOrDefault(dist,0)+1 );
				}
			}
		}

		print_histogram(distances);

/*
		//get "mean" from the distances histogram (that was accumulated over all time points)
		double distance = 0;
		long totalCnt = 0;
		for (int dist : distances.keySet()) {
			int cnt = distances.get(dist);
			distance += cnt * dist;
			totalCnt += cnt;
		}
		distance /= (double)totalCnt;
		System.out.println("Mean distance between the spots: "+distance);
		//distance /= 1.5; //4.0;
*/
		//find "max mode" distance in the histogram, and then when it decays to 1/10 on the right...
		int maxModeDistance = 0;
		int maxModeCnt = -1;
		for (int dist : distances.keySet()) {
			if (distances.get(dist) > maxModeCnt) {
				maxModeCnt = distances.get(dist);
				maxModeDistance = dist;
			}
		}
		int cntThres = Math.max( (int)Math.ceil((double)maxModeCnt / 10.0), 1 );
		double distance = 0;
		int maxDistance = maxModeDistance;
		for (int dist : distances.keySet()) {
			if (dist < maxModeDistance) continue;
			if (distances.get(dist) < cntThres) {
				distance = dist;
				break;
			}
			maxDistance = dist;
		}
		if (distance == 0) {
			//"emergency break" in case no "distance under the threshold" is found
			distance = 0.5 * (maxDistance + maxModeDistance);

			//yet another "emergency break" for single-cell images
			if (distance == 0) {
				distance = 1000;
				System.out.println("SINGLE CELL IMAGE?? using ad-host distance "+distance);
			}
		}
		System.out.println("mode cnt = "+maxModeCnt+" @ dist = "+maxModeDistance+", cntThres = "+cntThres+" => distance = "+distance);

		//since this is distance between cell centres (theoretically), we half it...
		//(to define how far two single cell centres are expected to travel until they crosses)
		distance /= 2.0;

		graph.releaseRef(spot);
		return distance;
	}


	static <K extends Number, V extends Number>
	void print_histogram(final Map<K,V> hist) {
		System.out.println("Histogram listing starts below...");
		for (K key : hist.keySet()) {
			V val = hist.get(key);
			System.out.println("HIST:\t"+key+"\t"+val);
		}
		System.out.println("Histogram listing ended above...");
	}

	static public void clearGraph(final ProjectModel projectModel) {
		Iterator<Spot> it = projectModel.getModel().getSpatioTemporalIndex().iterator();
		final ModelGraph graph = projectModel.getModel().getGraph();
		while (it.hasNext()) graph.remove( it.next() );
	}

	public static void main(String[] args) {
		ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Context ctx = ij.context();
		main(args,ctx);
	}

	public static void main(String[] args, final Context ctx) {
		if (args.length != 3 && args.length != 4) {
				System.out.println("Need three params in the following order:");
				System.out.println("  FULL_path/project.mastodon or path/filenameTemplate.tif");
				System.out.println("  first_time_point_to_track");
				System.out.println("  last_time_point_to_track");
				System.out.println("  [optional: FULL_path/save_result_into_this_project.mastodon]");
				return;
		}

		final int timeFrom = Integer.valueOf(args[1]);
		final int timeTill = Integer.valueOf(args[2]);
		ProjectModel projectModel;
		ImgProviders.ImgProvider imgProvider;
		try {
			if (args[0].endsWith(".mastodon")) {
				//real project
				System.out.println("Starting with mastodon project: "+args[0]);
				projectModel = ProjectLoader.open(args[0], ctx);
				imgProvider = new ImgProviders.ImgProviderFromMastodon(
						projectModel.getSharedBdvData().getSources().get(1).getSpimSource(),
						timeFrom );
				System.out.println(args[0]+" time span is "+timeFrom+" - "+timeTill);
			} else {
				//DUMMY dataset
				//TODO; make the dummy dataset size from the first real input image
				final String DUMMYXML="DUMMY x=100 y=100 z=100 t="+(timeTill+1)+".dummy";
				projectModel = ProjectModel.create(
						ctx,
						new Model(),
						SharedBigDataViewerData.fromDummyFilename(DUMMYXML),
						new MamutProject("ontheflyCTCproject.mastodon") );
				imgProvider = new ImgProviders.ImgProviderFromDisk(
						args[0],
						timeFrom );
			}

			clearGraph(projectModel);
			double distance = detect(projectModel,imgProvider, timeFrom,timeTill);
			link(projectModel, distance, timeFrom,timeTill);

/*
			//for review for now: show trackmate and bdv windows, and link them together
			projectModel.getWindowManager().createView(MamutViewTrackScheme.class)
					.getGroupHandle().setGroupId(0);
			projectModel.getWindowManager().createView(MamutViewBdv.class)
					.getGroupHandle().setGroupId(0);
*/
			if (args.length == 4) {
				Path path = Paths.get(args[3]);
				if (!path.isAbsolute()) path = path.toAbsolutePath();
				System.out.println("Saving tracked mastodon project: "+path);
				ProjectSaver.saveProject(path.toFile(), projectModel);
			} else {
				System.out.println("NOT saving tracked mastodon project");
			}

			ctx.dispose();
			System.exit(0);
		} catch (SpimDataException | IOException e) {
			throw new RuntimeException(e);
		}
	}
}
