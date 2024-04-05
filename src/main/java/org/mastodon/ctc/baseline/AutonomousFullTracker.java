package org.mastodon.ctc.baseline;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.SpimDataException;
import net.imagej.ImageJ;
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
		linkerSettings.put( KEY_GAP_CLOSING_MAX_DISTANCE, 1.3 * maxSearchDist );
		linkerSettings.put( KEY_ALLOW_TRACK_SPLITTING, true );
		linkerSettings.put( KEY_SPLITTING_MAX_DISTANCE, 0.7 * maxSearchDist );
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


	static <T extends IntegerType<T>>
	void findAndSetSpots(final RandomAccessibleInterval<T> img, final int time,
	                     final ModelGraph graph, final Spot auxSpot) {

		final Map<Integer,double[]> geomStats = new HashMap<>(500);
		int label;

		final Cursor<T> c = Views.iterable(img).localizingCursor();
		final double[] pos = new double[3];

		//fill up the stats
		while (c.hasNext()) {
			label = c.next().getInteger();
			if (label > 0) {
				double[] stat = geomStats.computeIfAbsent(label, k -> new double[4]);
				c.localize(pos);
				stat[0] += pos[0];
				stat[1] += pos[1];
				stat[2] += pos[2];
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
			findAndSetSpots((RandomAccessibleInterval)imageSrc.getImage(t),t, graph,spot);

			//analyze mutual spots distances
			SpatialIndex<Spot> index = projectModel.getModel().getSpatioTemporalIndex().getSpatialIndex(t);
			IncrementalNearestNeighborSearch<Spot> search = index.getIncrementalNearestNeighborSearch();
			for (Spot s : index) {
				int dist = 500;
				search.search(s);
				search.next();
				if (search.hasNext()) {
					dist = (int)Util.distance(search.next(),s);
					System.out.println("dist = "+dist);
				}
				distances.put(dist, distances.getOrDefault(dist,0)+1 );
			}
		}

		print_histogram(distances);

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

		graph.releaseRef(spot);
		return distance;
	}


	static <K extends Number, V extends Number>
	void print_histogram(final Map<K,V> hist) {
		System.out.println("Histogram listing starts below...");
		for (K key : hist.keySet()) {
			V val = hist.get(key);
			System.out.println(key+"\t"+val);
		}
		System.out.println("Histogram listing ended above...");
	}


	public static void main(String[] args) {
		if (args.length != 3) {
				System.out.println("Need three params in the following order:");
				System.out.println("  path/project.mastodon or path/folderName");
				System.out.println("  first_time_point_to_track");
				System.out.println("  last_time_point_to_track");
				return;
		}

		//for the right context...
		final ImageJ ij = new ImageJ();

		final int timeFrom = Integer.valueOf(args[1]);
		final int timeTill = Integer.valueOf(args[2]);
		ProjectModel projectModel;
		ImgProviders.ImgProvider imgProvider;
		try {
			if (args[0].endsWith(".mastodon")) {
				//real project
				projectModel = ProjectLoader.open(args[0], ij.context());
				imgProvider = new ImgProviders.ImgProviderFromMastodon(
						projectModel.getSharedBdvData().getSources().get(1).getSpimSource(),
						timeFrom );
			} else {
				//DUMMY dataset
				//TODO; make the dummy dataset size from the first real input image
				final String DUMMYXML="DUMMY x=100 y=100 z=100 t="+(timeTill+1)+".dummy";
				projectModel = ProjectModel.create(
						ij.getContext(),
						new Model(),
						SharedBigDataViewerData.fromDummyFilename(DUMMYXML),
						new MamutProject("ontheflyCTCproject.mastodon") );
				imgProvider = new ImgProviders.ImgProviderFromDisk(
						args[0],
						timeFrom );
			}

			double distance = detect(projectModel,imgProvider, timeFrom,timeTill);
			//distance = 70;
			link(projectModel, distance, timeFrom,timeTill);

			//show trackmate window for review for now...
			projectModel.getWindowManager().createView(MamutViewTrackScheme.class);

		} catch (SpimDataException | IOException e) {
			throw new RuntimeException(e);
		}
	}
}
