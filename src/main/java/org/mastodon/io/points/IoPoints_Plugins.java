package org.mastodon.io.points;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mastodon.app.ui.ViewMenuBuilder;
import org.mastodon.mamut.plugin.MamutPlugin;
import org.mastodon.mamut.plugin.MamutPluginAppModel;
import org.mastodon.mamut.MamutAppModel;
import org.mastodon.ui.keymap.CommandDescriptionProvider;
import org.mastodon.ui.keymap.CommandDescriptions;
import org.mastodon.ui.keymap.KeyConfigContexts;
import org.scijava.AbstractContextual;
import org.scijava.plugin.Plugin;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.util.RunnableAction;

import static org.mastodon.app.ui.ViewMenuBuilder.item;
import static org.mastodon.app.ui.ViewMenuBuilder.menu;

@Plugin( type = MamutPlugin.class )
public class IoPoints_Plugins extends AbstractContextual implements MamutPlugin
{
	private static final String IMPORT_FROM_IMAGES = "[tomancak] import from instance segmentation";

	private static final String POINTS_EXPORT_3COLS = "[tomancak] export spots as 3col points";
	private static final String POINTS_IMPORT_3COLS = "[tomancak] import spots from 3col points";
	private static final String POINTS_EXPORT_4COLS = "[tomancak] export spots as 4col points";
	private static final String POINTS_IMPORT_4COLS = "[tomancak] import spots from 4col points";

	private static final String[] IMPORT_FROM_IMAGES_KEYS = { "not mapped" };

	private static final String[] POINTS_EXPORT_3COLS_KEYS = { "not mapped" };
	private static final String[] POINTS_IMPORT_3COLS_KEYS = { "not mapped" };
	private static final String[] POINTS_EXPORT_4COLS_KEYS = { "not mapped" };
	private static final String[] POINTS_IMPORT_4COLS_KEYS = { "not mapped" };

	private static Map< String, String > menuTexts = new HashMap<>();

	static
	{
		menuTexts.put( IMPORT_FROM_IMAGES, "Import from instance segmentation" );

		menuTexts.put( POINTS_EXPORT_3COLS, "Export to 3-column files" );
		menuTexts.put( POINTS_IMPORT_3COLS, "Import from 3-column file" );
		menuTexts.put( POINTS_EXPORT_4COLS, "Export to 4-column file" );
		menuTexts.put( POINTS_IMPORT_4COLS, "Import from 4-column file" );
	}

	/*
	 * Command descriptions for all provided commands
	 */
	@Plugin( type = Descriptions.class )
	public static class Descriptions extends CommandDescriptionProvider
	{
		public Descriptions()
		{
			super( KeyConfigContexts.TRACKSCHEME, KeyConfigContexts.BIGDATAVIEWER );
		}

		@Override
		public void getCommandDescriptions( final CommandDescriptions descriptions )
		{
			descriptions.add(IMPORT_FROM_IMAGES,  IMPORT_FROM_IMAGES_KEYS,
					"Adds new spots from instance segmentation image series");
			descriptions.add(POINTS_EXPORT_3COLS, POINTS_EXPORT_3COLS_KEYS,
					"Exports spots x,y,z coordinates into text files in user-given folder, one file per one time point");
			descriptions.add(POINTS_IMPORT_3COLS, POINTS_IMPORT_3COLS_KEYS,
					"Adds new spots from a file with x,y,z coordinates, every spot creates own spatially-stationary track through-out the whole time-lapse");
			descriptions.add(POINTS_EXPORT_4COLS, POINTS_EXPORT_4COLS_KEYS,
					"Exports spots x,y,z,t coordinates into user-given text file");
			descriptions.add(POINTS_IMPORT_4COLS, POINTS_IMPORT_4COLS_KEYS,
					"Adds new spots from a file with x,y,z,t coordinates, no linking among the spots is done");
		}
	}

	private final AbstractNamedAction importFromImagesAction;

	private final AbstractNamedAction exportThreeColumnPointsPerTimepointsAction;
	private final AbstractNamedAction importThreeColumnPointsAction;
	private final AbstractNamedAction exportFourColumnPointsAction;
	private final AbstractNamedAction importFourColumnPointsAction;

	private MamutPluginAppModel pluginAppModel;

	public IoPoints_Plugins()
	{
		importFromImagesAction = new RunnableAction( IMPORT_FROM_IMAGES, this::importFromImages );

		exportThreeColumnPointsPerTimepointsAction = new RunnableAction( POINTS_EXPORT_3COLS, this::exportThreeColumnPointsPerTimepoints );
		importThreeColumnPointsAction              = new RunnableAction( POINTS_IMPORT_3COLS, this::importThreeColumnPoints );
		exportFourColumnPointsAction               = new RunnableAction( POINTS_EXPORT_4COLS, this::exportFourColumnPoints );
		importFourColumnPointsAction               = new RunnableAction( POINTS_IMPORT_4COLS, this::importFourColumnPoints );


		updateEnabledActions();
	}

	@Override
	public void setAppPluginModel( final MamutPluginAppModel model )
	{
		this.pluginAppModel = model;
		updateEnabledActions();
	}

	@Override
	public List< ViewMenuBuilder.MenuItem > getMenuItems()
	{
		return Arrays.asList(
				menu( "Plugins",
						menu( "Tomancak lab",
								menu( "Spots from/to TXT files",
									item( POINTS_EXPORT_3COLS ),
									item( POINTS_IMPORT_3COLS ),
									item( POINTS_EXPORT_4COLS ),
									item( POINTS_IMPORT_4COLS ) ),
								item( IMPORT_FROM_IMAGES ) ) ) );
	}

	@Override
	public Map< String, String > getMenuTexts()
	{
		return menuTexts;
	}

	@Override
	public void installGlobalActions( final Actions actions )
	{
		actions.namedAction( importFromImagesAction, IMPORT_FROM_IMAGES_KEYS );

		actions.namedAction( exportThreeColumnPointsPerTimepointsAction, POINTS_EXPORT_3COLS_KEYS );
		actions.namedAction( importThreeColumnPointsAction,              POINTS_IMPORT_3COLS_KEYS );
		actions.namedAction( exportFourColumnPointsAction,               POINTS_EXPORT_4COLS_KEYS );
		actions.namedAction( importFourColumnPointsAction,               POINTS_IMPORT_4COLS_KEYS );
	}

	private void updateEnabledActions()
	{
		final MamutAppModel appModel = ( pluginAppModel == null ) ? null : pluginAppModel.getAppModel();
		importFromImagesAction.setEnabled( appModel != null );

		exportThreeColumnPointsPerTimepointsAction.setEnabled( appModel != null );
		importThreeColumnPointsAction.setEnabled( appModel != null );
		exportFourColumnPointsAction.setEnabled( appModel != null );
		importFourColumnPointsAction.setEnabled( appModel != null );
	}

	private void importFromImages()
	{
		if ( pluginAppModel == null ) return;

		this.getContext().getService(CommandService.class).run(
			ReadInstanceSegmentationImages.class, true,
			"appModel", pluginAppModel.getAppModel(),
			"logService", this.getContext().getService(LogService.class));
	}

	private void exportThreeColumnPointsPerTimepoints()
	{
		if ( pluginAppModel != null )
			WritePointsTXT.exportThreeColumnPointsPerTimepoints( pluginAppModel.getAppModel() );
	}
	private void importThreeColumnPoints()
	{
		if ( pluginAppModel != null )
			ReadPointsTXT.importThreeColumnPoints( pluginAppModel.getAppModel() );
	}

	private void exportFourColumnPoints()
	{
		if ( pluginAppModel != null )
			WritePointsTXT.exportFourColumnPoints( pluginAppModel.getAppModel() );
	}
	private void importFourColumnPoints()
	{
		if ( pluginAppModel != null )
			ReadPointsTXT.importFourColumnPoints( pluginAppModel.getAppModel() );
	}
}
