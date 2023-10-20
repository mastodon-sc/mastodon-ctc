package org.mastodon.io.points;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.mastodon.collection.RefList;
import org.mastodon.mamut.plugin.MamutPluginAppModel;
import org.mastodon.ui.util.FileChooser;
import org.mastodon.ui.util.ExtensionFileFilter;
import net.imglib2.realtransform.AffineTransform3D;
import org.mastodon.spatial.SpatioTemporalIndex;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.model.Link;
import org.mastodon.collection.RefIntMap;
import org.mastodon.collection.RefMaps;
import org.mastodon.collection.RefCollections;
import org.mastodon.collection.RefCollection;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.log.Logger;


public class PlainTextFileExport
{
	private final MamutPluginAppModel pluginAppModel;
	private final LogService logService;
	private Logger logger;

	public PlainTextFileExport(final MamutPluginAppModel appModel, final LogService logService) {
		this.pluginAppModel = appModel;
		this.logService = logService;
	}

	/** opens the output file dialog, runs the export,
	    and pops-up a "done+hints" message window */
	public void exporter()
	{
		//open a folder choosing dialog
		final File selectedFile = FileChooser.chooseFile(null, null,
				new ExtensionFileFilter("txt"),
				"Choose TXT file to write tracks into:",
				FileChooser.DialogType.SAVE,
				FileChooser.SelectionMode.FILES_ONLY);

		//cancel button ?
		if (selectedFile == null) return;

		logger = logService.subLogger("Exporting plain text file");

		//writing params:
		final String delim = "\t";
		final int timeFrom = pluginAppModel.getAppModel().getMinTimepoint();
		final int timeTill = pluginAppModel.getAppModel().getMaxTimepoint();

		//shortcuts to the data
		final Model      model      = pluginAppModel.getAppModel().getModel();
		final ModelGraph modelGraph = model.getGraph();

		AffineTransform3D transform = new AffineTransform3D();
		pluginAppModel.getAppModel().getSharedBdvData().getSources().get(0).getSpimSource().getSourceTransform(0,0, transform);
		transform = transform.inverse();
		//NB: is now world2img transform
		//-------------------------------------------------

		//aux conversion data, also provides trees of tracks and lists of spots per track
		final RichTrackRecords tracks = new RichTrackRecords( modelGraph.vertices() );

		//map: Mastodon's spotID to our/sequential trackID
		RefIntMap< Spot > knownTracks = RefMaps.createRefIntMap( modelGraph.vertices(), -1, 500 );

		//aux Mastodon data: shortcuts and caches/proxies
		final SpatioTemporalIndex< Spot > spots = model.getSpatioTemporalIndex();
		final Link lRef = modelGraph.edgeRef();              //link reference
		final Spot sRef = modelGraph.vertices().createRef(); //spot reference
		final Spot fRef = modelGraph.vertices().createRef(); //some spot's future buddy

		try
		{

		//over all time points
		for (int time = timeFrom; time <= timeTill; ++time)
		{
			//over all spots in the current time point
			for ( final Spot spot : spots.getSpatialIndex( time ) )
			{
				//find how many back- and forward-references (time-wise) this spot has
				int countBackwardLinks = 0;
				int countForwardLinks = 0;

				for (int n=0; n < spot.incomingEdges().size(); ++n)
				{
					spot.incomingEdges().get(n, lRef).getSource( sRef );
					if (sRef.getTimepoint() < time && sRef.getTimepoint() >= timeFrom) ++countBackwardLinks;
					if (sRef.getTimepoint() > time && sRef.getTimepoint() <= timeTill)
					{
						++countForwardLinks;
						fRef.refTo( sRef );
					}
				}
				for (int n=0; n < spot.outgoingEdges().size(); ++n)
				{
					spot.outgoingEdges().get(n, lRef).getTarget( sRef );
					if (sRef.getTimepoint() < time && sRef.getTimepoint() >= timeFrom) ++countBackwardLinks;
					if (sRef.getTimepoint() > time && sRef.getTimepoint() <= timeTill)
					{
						++countForwardLinks;
						fRef.refTo( sRef );
					}
				}

				//process events:
				//
				//feasibility test: too many joining paths? (aka merging event)
				if (countBackwardLinks > 1)
				{
					logger.log(LogLevel.ERROR,
					                  "spot "+spot.getLabel()
					                  +" has multiple ("+countBackwardLinks
					                  +") older-time-point links!");

					//ideally should stop here, but we opted to finish all tracks
					//that join this one, and start the new (parentID = 0) track here

					//list backward links and just forget them (aka delete them from knownTracks)
					for (int n=0; n < spot.incomingEdges().size(); ++n)
					{
						spot.incomingEdges().get(n, lRef).getSource( sRef );
						if (sRef.getTimepoint() < time && sRef.getTimepoint() >= timeFrom)
							knownTracks.remove( sRef );
					}
					for (int n=0; n < spot.outgoingEdges().size(); ++n)
					{
						spot.outgoingEdges().get(n, lRef).getTarget( sRef );
						if (sRef.getTimepoint() < time && sRef.getTimepoint() >= timeFrom)
							knownTracks.remove( sRef );
					}

					//a new track from this spot must be existing because some from the backward
					//links must have created it, and creating it means either it is a single-follower
					//in which case we must remove this track (just abandon it), or it is a
					//one-from-many-follower (division) in which case the track has just been started
					//(which is OK) and has parent info set (which is not desired now); in the latter
					//case and since we cannot modify existing track, we just delete it
					//
					//and by re-setting backward links, new track will start just in the code below
					countBackwardLinks = 0;

					if (tracks.getStartTimeOfTrack( knownTracks.get(spot) ) == time)
					{
						//the track 'ID' would have been just starting here,
						//re-starting really means to remove it first
						tracks.removeTrack( knownTracks.get(spot) );
						logger.trace(spot.getLabel()+": will supersede track ID "+knownTracks.get(spot));
					}
					else
					{
						logger.trace(spot.getLabel()+": will just leave the track ID "+knownTracks.get(spot));
					}
				}

				//spot with no backward links?
				if (countBackwardLinks == 0)
				{
					//start a new track
					knownTracks.put( spot, tracks.startNewTrack( spot, time, 0 ) );
					logger.trace(spot.getLabel()+": started track ID "+knownTracks.get(spot)+" at time "+spot.getTimepoint());
				}
				else //countBackwardLinks == 1
				{
					//prolong the existing track
					tracks.updateTrack( spot, knownTracks.get(spot), time );
					logger.trace(spot.getLabel()+": updated track ID "+knownTracks.get(spot)+" at time "+spot.getTimepoint());
				}

				//multiple "followers"? feels like a division...
				if (countForwardLinks > 1)
				{
					//list forward links and create them at their respective times,
					//mark spot as their parent
					for (int n=0; n < spot.incomingEdges().size(); ++n)
					{
						spot.incomingEdges().get(n, lRef).getSource( sRef );
						if (sRef.getTimepoint() > time && sRef.getTimepoint() <= timeTill)
						if (knownTracks.get(sRef) == -1)
						{
							knownTracks.put( sRef, tracks.startNewTrack( sRef, sRef.getTimepoint(), knownTracks.get(spot) ) );
							logger.trace(sRef.getLabel()+": started track ID "+knownTracks.get(sRef)+" at time "+sRef.getTimepoint());
						}
					}
					for (int n=0; n < spot.outgoingEdges().size(); ++n)
					{
						spot.outgoingEdges().get(n, lRef).getTarget( sRef );
						if (sRef.getTimepoint() > time && sRef.getTimepoint() <= timeTill)
						if (knownTracks.get(sRef) == -1)
						{
							knownTracks.put( sRef, tracks.startNewTrack( sRef, sRef.getTimepoint(), knownTracks.get(spot) ) );
							logger.trace(sRef.getLabel()+": started track ID "+knownTracks.get(sRef)+" at time "+sRef.getTimepoint());
						}
					}
				}
				else if (countForwardLinks == 1)
				{
					//just one follower, is he right in the next frame?
					if (fRef.getTimepoint() == time+1)
					{
						//yes, just replace myself in the map
						if (knownTracks.get(fRef) == -1)
							knownTracks.put( fRef, knownTracks.get(spot) );
					}
					else
					{
						//no, start a new track for the follower
						if (knownTracks.get(fRef) == -1)
						{
							knownTracks.put( fRef, tracks.startNewTrack( fRef, fRef.getTimepoint(), knownTracks.get(spot) ) );
							logger.trace(fRef.getLabel()+": started track ID "+knownTracks.get(fRef)+" at time "+fRef.getTimepoint());
						}
					}
				}

				//forget the currently closed track
				knownTracks.remove( spot );

				//debug: report currently knownTracks
				/*
				for (final Spot s : knownTracks.keySet())
					System.out.println(s.getLabel()+" -> "+knownTracks.get(s));
				*/
			}
		}

		//aux storage of coords
		final double[] coords = new double[3];

		final BufferedWriter f
			= new BufferedWriter( new FileWriter(selectedFile.getAbsolutePath()) );

		f.write("# from project "+pluginAppModel.getWindowManager().getProjectManager().getProject().getProjectRoot().getAbsolutePath());
		f.newLine();
		f.write("# TIME"+delim+"X"+delim+"Y"+delim+"Z"+delim+"TRACK_ID"+delim+"PARENT_TRACK_ID"+delim+"SPOT LABEL");
		f.newLine();
		f.newLine();

		for (Set<Integer> tree : tracks.forestOfTrackTrees)
		{
			//first, report the complete tree
			f.write("# one tree of tracks:"); f.newLine();
			f.write("#"+delim);
			for (Integer ID : tree) f.write(ID+delim);
			f.newLine();

			//second, report every track of this tree separately
			for (Integer ID : tree)
			{
				final int parentID = tracks.getParentOfTrack(ID);
				for (Spot s : tracks.spotsLists.get(ID))
				{
					//convert spot's coordinate into underlying image coordinate system
					s.localize(coords);
					transform.apply(coords,coords);

					f.write(s.getTimepoint()+delim
					       +coords[0]+delim
					       +coords[1]+delim
					       +coords[2]+delim
					       +ID+delim
					       +parentID+delim
					       +s.getLabel());
					f.newLine();
				}
				f.newLine();
				f.newLine();
			}
		}

		f.close();

		}
		catch (IOException e) {
			//report the original error message further
			e.printStackTrace();
		}

		//pop-up a "done and hints" just-OK-button window
		logger.info("done.");
		//this.context().getService(LogService.class).log().info("Wrote file: "+selectedFile.getAbsolutePath());
	}


	class RichTrackRecords extends net.celltrackingchallenge.measures.TrackRecords
	{
		//reference on the pool of vertices that we need for the RefLists below
		final private RefCollection<Spot> poolOfVertices;

		RichTrackRecords(final RefCollection<Spot> pool)
		{
			super();
			poolOfVertices = pool;
		}
		//--------------------

		//sets with trees: one set holds IDs of all tracks that belong to the same tree
		private final ArrayList<Set<Integer>> forestOfTrackTrees = new ArrayList<>(200);

		//chronological/ordered lists of Mastodon spots that each make up one track
		private final HashMap<Integer,RefList<Spot>> spotsLists = new HashMap<>();

		public int startNewTrack(final Spot spotRef, final int curTime, final int parentID)
		{
			//the old behaviour
			final int ID = super.startNewTrack(curTime,parentID);

			//the added-behaviour stuff:
			//list of spots that make up this track:
			spotsLists.put(ID, RefCollections.createRefList( poolOfVertices, 1000 ));
			spotsLists.get(ID).add(spotRef);

			//forest of trees:
			if (parentID > 0)
			{
				boolean foundTree = false;
				int sizeBefore=0, sizeAfter=0;

				//find a tree containing my parent, and append myself to it
				for (Set<Integer> tree : forestOfTrackTrees)
				if (tree.contains(parentID))
				{
					foundTree = true;
					sizeBefore = tree.size();
					tree.add(ID);
					sizeAfter  = tree.size();
					break;
				}

				//should not happen that we had not added myself but still having parentID > 0
				if (!foundTree)
				{
					logger.log(LogLevel.ERROR,
					                  "track "+ID
					                  +" could not find the track tree of its parent "+parentID);
				}
				else if (sizeBefore == sizeAfter)
				{
					logger.log(LogLevel.ERROR,
					                  "could not add track "+ID
					                  +" to the track tree of its parent "+parentID);
				}
			}
			else
			{
				//start up a new track tree that contains only this track (for now)
				forestOfTrackTrees.add( new HashSet<>(Collections.singleton(ID)) );
			}

			//the old behaviour
			return ID;
		}

		public void updateTrack(final Spot spotRef, final int ID, final int curTime)
		{
			//the old behaviour
			super.updateTrack(ID,curTime);

			//the added-behaviour stuff:
			//list of spots that make up this track:
			if ( !spotsLists.get(ID).contains(spotRef) )
				spotsLists.get(ID).add(spotRef);
			//NB: after divisions, the daughters are already listed here due to startNewTrack()
		}

		@Override
		public void removeTrack(final int ID)
		{
			final Object wasItEverHere = this.tracks.get(ID);

			//the old behaviour
			super.removeTrack(ID);

			if (wasItEverHere != null)
			{
				//the added-behaviour stuff:
				//list of spots that make up this track:
				spotsLists.get(ID).clear(); //remove content of the refList
				//TODO: unregister somehow the refList? counteraction to getRefPool() in RefCollections.createRefList()
				spotsLists.remove(ID);

				//forest of trees:
				//find a tree containing myself and remove myself from it
				for (Set<Integer> tree : forestOfTrackTrees)
				if (tree.contains(ID))
				{
					tree.remove(ID);
					break;
				}
			}
			else
			{
				logger.log(LogLevel.ERROR,
				                  "could not remove non-existing track "+ID);
			}
		}
	}
}
