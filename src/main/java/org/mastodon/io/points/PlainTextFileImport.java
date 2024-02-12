package org.mastodon.io.points;

import net.imglib2.realtransform.AffineTransform3D;
import org.mastodon.collection.IntRefMap;
import org.mastodon.collection.ref.IntRefHashMap;
import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.log.Logger;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Plugin( type = Command.class, name = "Importer of spots from plain text file @ Mastodon" )
public class PlainTextFileImport implements Command
{
	@Parameter(
			label = "Choose TXT file to read tracks from:",
			style = FileWidget.OPEN_STYLE)
	File selectedFile;

	@Parameter(label = "Use radii from the file where available:")
	boolean preferRadiusColumn = false;

	@Parameter(label = "Use this radius as fallback:", min = "1")
	double defaultRadius = 10.0;

	@Parameter(persist = false)
	ProjectModel projectModel;

	@Parameter
	LogService logService;

	@Override
	public void run()
	{
		if (selectedFile == null) return;

		final Logger logger = logService.subLogger("Importing plain text file");

		final AffineTransform3D transform = new AffineTransform3D();
		projectModel.getSharedBdvData().getSources().get(0)
				.getSpimSource().getSourceTransform(0,0, transform);
		//NB: is now img2world transform
		final double[] coord = new double[3];

		final ModelGraph graph = projectModel.getModel().getGraph(); //shortcut only...
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
		//child track -> parent track (cannot be the opposite as parent can have multiple children...)
		Map<Integer,Integer> missingLinks = new HashMap<>(100);
		//
		Spot spotNew = graph.vertices().createRef();
		Spot spotLastOnTheTrack = graph.vertices().createRef();

		try ( BufferedReader lineReader = new BufferedReader(new FileReader(selectedFile.getAbsolutePath())) ) {
			String line = lineReader.readLine();
			int lineCounter = 1;
			while (line != null) {
				//assuming a full "branch block" has been read, and we're now before another one
				if (line.length() == 0 || line.startsWith("#")) {
					//getting ready for the next line...
					line = lineReader.readLine();
					++lineCounter;
					continue;
				}

				//'line' shows the first line of a "branch block"
				String[] tokens = line.split("\t");
				if (tokens.length != 7 && tokens.length != 8)
					throw new IOException(line+" (line no. "+lineCounter
							+") doesn't seem to be a line defining one spot.");

				int tp = Integer.parseInt(tokens[0]);
				coord[0] = Double.parseDouble(tokens[1]);
				coord[1] = Double.parseDouble(tokens[2]);
				coord[2] = Double.parseDouble(tokens[3]);
				transform.apply(coord, coord);
				int currentTrackID = Integer.parseInt(tokens[4]);
				int parentTrackID = Integer.parseInt(tokens[5]);
				//token[6] is the spot label
				//token[7] may include a radius
				double radius = preferRadiusColumn && tokens.length == 8 ? Double.parseDouble(tokens[7]) : defaultRadius;
				//
				//first, we create the new spot representing the just parsed line
				graph.addVertex( spotNew ).init(tp, coord, radius).setLabel( tokens[6] );

				if ( ! trackToItsLastSpot.keySet().contains( currentTrackID ) ) {
					//starting a new track
					trackToItsFirstSpot.put(currentTrackID, spotNew);
					trackToItsLastSpot.put(currentTrackID, spotNew);
					logger.debug("read line no. "+lineCounter+": "+line);
					logger.debug("Introducing a new track "+currentTrackID+" with label "+tokens[6]+" at timepoint "+tp);

					if (parentTrackID > 0) {
						//...which happens to a be a daughter track
						if ( trackToItsLastSpot.keySet().contains( parentTrackID ) ) {
							//...to an already loaded/existing track, we just connect them
							trackToItsLastSpot.get(parentTrackID, spotLastOnTheTrack);
							graph.addEdge(spotLastOnTheTrack, spotNew).init();
							logger.debug("    with a link to a parent track "+parentTrackID+" to label "+spotLastOnTheTrack.getLabel());
						} else {
							//...to a track yet to be discovered, we just take a note
							missingLinks.put(currentTrackID, parentTrackID);
							logger.debug("    with a MISSING link to a parent track "+parentTrackID);
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
				trackToItsFirstSpot.get(link.getKey(), spotNew);
				trackToItsLastSpot.get(link.getValue(), spotLastOnTheTrack);
				graph.addEdge(spotLastOnTheTrack, spotNew).init();
				logger.debug("Adding MISSING links from track "+link.getValue()+" (label "+spotLastOnTheTrack.getLabel()
						+") to track "+link.getKey()+" (label "+spotNew.getLabel()+")");
			}
		} catch (IOException e) {
			logger.error("Error reading the input image: "+e.getMessage());
		} catch (NumberFormatException e) {
			logger.error("Failed parsing input value: "+e.getMessage());
		}

		graph.vertices().releaseRef(spotNew);
		graph.vertices().releaseRef(spotLastOnTheTrack);
		logger.info("Import from plain text file: done.");
	}
}
