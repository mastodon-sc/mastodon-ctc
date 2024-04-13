package org.mastodon.ctc.baseline;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import ij.IJ;
import net.imagej.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.img.planar.PlanarImgFactory;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.mastodon.collection.RefSet;
import org.mastodon.collection.ref.RefSetImp;
import org.mastodon.mamut.io.ProjectSaver;
import org.mastodon.util.DepthFirstIteration;
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
		System.out.printf("Tracking successful. Done in %.1f s.%n", ( end - start ) / 1000. );
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
		for (int geomLabel : geomStats.keySet()) {
			double[] stat = geomStats.get(geomLabel);
			pos[0] = stat[0] / stat[3];
			pos[1] = stat[1] / stat[3];
			pos[2] = stat[2] / stat[3];
			graph.addVertex(auxSpot).init(time,pos,3);
			auxSpot.setLabel("L "+geomLabel+" minBox "
					+ (int)stat[4] + " " + (int)stat[5] + " " + (int)stat[6]
					+ " maxBox "
					+ (int)stat[7] + " " + (int)stat[8] + " " + (int)stat[9]);
		}
		System.out.println("TP "+time+" found "+geomStats.size()+" spots");
	}

	static <O extends IntegerType<O>, L extends IntegerType<L>>
	void fillSpots(final RandomAccessibleInterval<O> outImg,
	               final RandomAccessibleInterval<L> labelImg,
	               final SpatialIndex<Spot> spots) {

		long[] minCorner = new long[3];
		long[] maxCorner = new long[3];
		for (Spot s : spots) {
			String[] items = s.getLabel().split(" ");
			int inputLabel = Integer.parseInt(items[1]);
			int outputLabel = Integer.parseInt(items[11]);
			minCorner[0] = Long.parseLong(items[3]);
			minCorner[1] = Long.parseLong(items[4]);
			minCorner[2] = Long.parseLong(items[5]);
			maxCorner[0] = Long.parseLong(items[7]);
			maxCorner[1] = Long.parseLong(items[8]);
			maxCorner[2] = Long.parseLong(items[9]);
			Interval roi = new FinalInterval(minCorner,maxCorner);
			LoopBuilder.setImages( Views.interval(outImg,roi),Views.interval(labelImg,roi) )
					  .forEachPixel((o,l) -> {
						  if (l.getInteger() == inputLabel) o.setInteger(outputLabel);
					  });
		}
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


	/** returns a map with the CTC tracks.txt quartets */
	static public Map<Integer,Integer[]> establishAndNoteTracks(final ProjectModel projectModel) {
		final ModelGraph graph = projectModel.getModel().getGraph();

		RefSet<Spot> roots = new RefSetImp<>(graph.vertices().getRefPool(),100);
		//TODO: parallelStream? is roots::add thread-safe?
		graph.vertices().stream().filter(s -> s.incomingEdges().isEmpty()).forEach(roots::add);

		Map<Integer,Integer[]> tt = new HashMap<>(10000);

		int trackID = 1;
		for (Spot root : roots) {
			Integer[] quartet = new Integer[] {trackID, root.getTimepoint(), -1, 0};
			tt.put(trackID,quartet);
			//System.out.println("Using trackID "+trackID+" from root "+root.getLabel()+" @ tp "+root.getTimepoint());
			boolean tt_initTrack = false;

			for (DepthFirstIteration.Step<Spot> step : DepthFirstIteration.forRoot(graph,root)) {
				final Spot spot = step.node();
				if (step.isFirstVisit() || step.isLeaf()) {
					//label only once
					spot.setLabel(spot.getLabel()+" Track "+trackID);
					if (tt_initTrack) {
						int trackParentID = Integer.parseInt(spot.incomingEdges().get(0).getSource().getLabel().split(" ")[11]);
						quartet = new Integer[] {trackID, spot.getTimepoint(), -1, trackParentID};
						tt.put(trackID,quartet);
						tt_initTrack = false;
					}

					if (spot.outgoingEdges().size() > 1 || step.isLeaf()) {
						tt.get(trackID)[2] = spot.getTimepoint();
						//division point? -> increase trackID (to be used immediately in the current down-the-tree pass)
						//NB: every division point is visited only once on the down-the-tree pass,
						//    but the traversal returns directly under some division point on its
						//    up-the-tree pass, which starts after "turning itself" at the leaves,
						//thus, leaf? -> increase trackID (to be used again on the down-the-three pass)
						++trackID;
						tt_initTrack = true;
						//System.out.println("Increase trackID to "+trackID+" at spot "+spot.getLabel()+" @ tp "+spot.getTimepoint());
					}
				}
			}
		}

		return tt;
	}


	public static void main(String[] args) {
		ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Context ctx = ij.context();
		main(args,ctx);
	}

	public static void main(String[] args, final Context ctx) {
		if (args.length != 4 && args.length != 5) {
				System.out.println("Need three params in the following order:");
				System.out.println("  FULL_path/project.mastodon or path/filenameTemplate.tif");
				System.out.println("  ztoxratio (ignored in the input case of .mastodon)");
				System.out.println("  first_time_point_to_track");
				System.out.println("  last_time_point_to_track");
				System.out.println("  [optional: FULL_path/save_result_into_this_project.mastodon]");
				System.out.println("  [      or: output_path/filenameTemplate.tif, which would]");
				System.out.println("  [          also create output_path/res_track.txt]");
				return;
		}

		final float zToxRatio = Float.parseFloat(args[1]);
		final int timeFrom = Integer.parseInt(args[2]);
		final int timeTill = Integer.parseInt(args[3]);
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
				imgProvider = new ImgProviders.ImgProviderFromDisk(
						  args[0],
						  timeFrom );
				//dummy dataset, of the same size as the first real input image
				RandomAccessibleInterval<?> img = imgProvider.getImage(timeFrom); //NB: will not read TP = timeFrom again
				final long xSize = img.dimension(0);
				final long ySize = img.dimension(1);
				final long zSize = img.numDimensions() > 2 ? img.dimension(2) : 1L;
				final String DUMMYXML="DUMMY x="+xSize+" y="+ySize+" z="+zSize+" t="+(timeTill+1)+".dummy";
				projectModel = ProjectModel.create(
						ctx,
						new Model(),
						SharedBigDataViewerData.fromDummyFilename(DUMMYXML),
						new MamutProject("ontheflyCTCproject.mastodon") );
			}

			clearGraph(projectModel);
			double distance = detect(projectModel,imgProvider, timeFrom,timeTill);
			link(projectModel, distance, timeFrom,timeTill);
			Map<?,Integer[]> tt = establishAndNoteTracks(projectModel);

			if (args.length == 5) {
				Path path = Paths.get(args[4]);
				if (!path.isAbsolute()) path = path.toAbsolutePath();
				//
				if (args[4].endsWith(".mastodon")) {
					System.out.println("Saving tracked mastodon project: "+path);
					ProjectSaver.saveProject(path.toFile(), projectModel);
				} else {
					//da plan: iterate all TPs.for each( read, all spots relabel, write ) using threads
					final String resFile= Paths
							  .get( path.getParent().toString(), "res_track.txt" )
							  .toString();
					System.out.println("Writing file "+resFile);
					try ( BufferedWriter bw = new BufferedWriter(new FileWriter(resFile)) ) {
						for (Integer[] t : tt.values()) {
							bw.write(t[0] + " " + t[1] + " " + t[2] + " " + t[3]);
							bw.newLine();
						}
					}

					final Collection<Callable<Integer>> tasks = new ArrayList<>(timeTill-timeFrom+1);
					for (int time = timeFrom; time <= timeTill; ++time) {
						tasks.add(new Relabeler(projectModel, imgProvider, args[4], time));
					}
					Executors.newFixedThreadPool(IO_PARALLEL_WORKERS_COUNT).invokeAll(tasks);
				}
			} else {
				System.out.println("NOT saving the tracked project");
			}

			ctx.dispose();
			System.exit(0);
		} catch (SpimDataException | IOException | InterruptedException  e) {
			throw new RuntimeException(e);
		}
	}


	static private final int IO_PARALLEL_WORKERS_COUNT = 4;

	private static class Relabeler implements Callable<Integer> {

		Relabeler(final ProjectModel projectModel,
		          final ImgProviders.ImgProvider imageSrc,
		          final String outFilenameTemplate,
		          int forThisTimepoint) {
			this.projectModel = projectModel;
			this.imageSrc = imageSrc;
			this.outFilenameTemplate = outFilenameTemplate;
			this.time = forThisTimepoint;
		}

		private final ProjectModel projectModel;
		private final ImgProviders.ImgProvider imageSrc;
		private final String outFilenameTemplate;
		private final int time;

		@Override
		public Integer call() {
			System.out.println("Relabeler TP="+time+": reading input image...");
			RandomAccessibleInterval<?> labelImg = imageSrc.getImage(time);

			//System.out.println("Relabeler TP="+time+": creating output image...");
			RandomAccessibleInterval<UnsignedShortType> outImg
					  = new PlanarImgFactory<>(new UnsignedShortType()).create(labelImg);

			//System.out.println("Relabeler TP="+time+": filling output image...");
			fillSpots(outImg, (RandomAccessibleInterval)labelImg, projectModel.getModel().getSpatioTemporalIndex().getSpatialIndex(time));

			String outFileName = String.format(outFilenameTemplate,time);
			System.out.println("Relabeler TP="+time+": saving output image to "+outFileName);
			IJ.save(ImageJFunctions.wrap(outImg, "Mastodon-CTC-export"), outFileName);

			return time;
		}
	}
}
