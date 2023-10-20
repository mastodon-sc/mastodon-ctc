package org.mastodon.io.points;

import net.imglib2.realtransform.AffineTransform3D;
import org.mastodon.collection.IntRefMap;
import org.mastodon.collection.ref.IntRefHashMap;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.mamut.plugin.MamutPluginAppModel;
import org.mastodon.ui.util.ExtensionFileFilter;
import org.mastodon.ui.util.FileChooser;
import org.scijava.log.LogService;
import org.scijava.log.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PlainTextFileImport
{
	private final MamutPluginAppModel pluginAppModel;
	private final LogService logService;

	public PlainTextFileImport(final MamutPluginAppModel appModel, final LogService logService) {
		this.pluginAppModel = appModel;
		this.logService = logService;
	}

	public void importer() {
		final File selectedFile = FileChooser.chooseFile(null, null,
				new ExtensionFileFilter("txt"),
				"Choose TXT file to write tracks into:",
				FileChooser.DialogType.SAVE,
				FileChooser.SelectionMode.FILES_ONLY);

		//cancel button ?
		if (selectedFile == null) return;

		final Logger logger = logService.subLogger("Importing plain text file");

		final AffineTransform3D transform = new AffineTransform3D();
		pluginAppModel.getAppModel().getSharedBdvData().getSources().get(0)
				.getSpimSource().getSourceTransform(0,0, transform);
		//NB: is now img2world transform
		final double coord[] = new double[3];

		final ModelGraph graph = pluginAppModel.getAppModel().getModel().getGraph(); //shortcut only...
		//
		//maintain info about the last spot in a given track
		IntRefMap<Spot> trackToItsLastSpot = new IntRefHashMap<>(
				graph.vertices().getRefPool(),
				-1,
				1000);
		//..and about the first spot in a given track
		IntRefMap<Spot> trackToItsFirstSpot = new IntRefHashMap<>(
				graph.vertices().getRefPool(),
				-1,
				1000);
		//
		Map<Integer,Integer> missingLinks = new HashMap<>(100);
		//
		Spot spotNew = graph.vertices().createRef();
		Spot spotLastOnTheTrack = graph.vertices().createRef();

		try ( BufferedReader lineReader = new BufferedReader(new FileReader(selectedFile.getAbsolutePath())) ) {
			String line = lineReader.readLine();
			int lineCounter = 1;
			while (line != null) {
				//assuming a full "branch block" has been read, and we're now before another one
				if (line.length() == 0 || line.startsWith("#")) continue;

				//'line' shows the first line of a "branch block"
				String tokens[] = line.split("\t");
				if (tokens.length != 7)
					throw new IOException(line+" (line no. "+lineCounter
							+") doesn't seem to be a line defining one spot.");

				//TODO parsing exception!!
				int tp = Integer.parseInt(tokens[0]);
				coord[0] = Double.parseDouble(tokens[1]);
				coord[1] = Double.parseDouble(tokens[2]);
				coord[2] = Double.parseDouble(tokens[3]);
				transform.apply(coord, coord);
				int currentTrackID = Integer.parseInt(tokens[4]);
				int parentTrackID = Integer.parseInt(tokens[5]);
				//token[6] is the spot label
				//
				//first, we create the new spot representing the just parsed line
				graph.addVertex( spotNew ).init(tp, coord, 1.0).setLabel( tokens[6] );

				if ( ! trackToItsLastSpot.keySet().contains( currentTrackID ) ) {
					//starting a new track
					trackToItsFirstSpot.put(currentTrackID, spotNew);
					trackToItsLastSpot.put(currentTrackID, spotNew);

					if (parentTrackID > 0) {
						//...which happens to a be a daughter track
						if ( trackToItsLastSpot.keySet().contains( parentTrackID ) ) {
							//...to an already loaded/existing track, we just connect them
							trackToItsLastSpot.get(parentTrackID, spotLastOnTheTrack);
							graph.addEdge(spotLastOnTheTrack, spotNew).init();
						} else {
							//...to a track yet to be discovered, we just take a note
							missingLinks.put(parentTrackID, currentTrackID);
						}
					}
				} else {
					//continuing on an already started track
					trackToItsLastSpot.get(currentTrackID, spotLastOnTheTrack);
					graph.addEdge(spotLastOnTheTrack, spotNew).init();
					//
					trackToItsLastSpot.put(currentTrackID, spotNew); //updates who's the last now
				}

				//getting ready for the next line...
				line = lineReader.readLine();
				++lineCounter;
			}

			//don't forget to add all the missing links
			for (Map.Entry<Integer,Integer> link : missingLinks.entrySet()) {
				trackToItsLastSpot.get(link.getKey(), spotLastOnTheTrack);
				trackToItsFirstSpot.get(link.getValue(), spotNew);
				graph.addEdge(spotLastOnTheTrack, spotNew).init();
			}
		} catch (IOException e) {
			logger.error("Error reading the input image: "+e.getMessage());
		}

		graph.vertices().releaseRef(spotNew);
		graph.vertices().releaseRef(spotLastOnTheTrack);
	}
}
