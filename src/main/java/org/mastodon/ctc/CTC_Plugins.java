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
package org.mastodon.ctc;

import static org.mastodon.app.ui.ViewMenuBuilder.item;
import static org.mastodon.app.ui.ViewMenuBuilder.menu;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.mastodon.app.ui.ViewMenuBuilder;
import org.mastodon.mamut.plugin.MamutPlugin;
import org.mastodon.mamut.KeyConfigScopes;
import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.model.Model;
import org.mastodon.ui.keymap.KeyConfigContexts;

import org.scijava.log.LogService;
import org.scijava.AbstractContextual;
import org.scijava.command.CommandService;
import org.scijava.plugin.Plugin;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.io.gui.CommandDescriptionProvider;
import org.scijava.ui.behaviour.io.gui.CommandDescriptions;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.RunnableAction;
import net.imglib2.type.numeric.integer.UnsignedShortType;

@Plugin( type = MamutPlugin.class )
public class CTC_Plugins extends AbstractContextual implements MamutPlugin
{
	//"IDs" of all plug-ins wrapped in this class
	private static final String CTC_IMPORT = "[ctc] import all";
	private static final String CTC_EXPORT = "[ctc] export all";
	private static final String CTC_TRA_CHECKER = "[ctc] review TRA";
	private static final String CTC_TRA_ADJUSTER = "[ctc] adjust TRA";
	private static final String CTC_TRA_ADJUSTER_NQ = "[ctc] adjust TRA w/o dialog";

	private static final String[] CTC_IMPORT_KEYS = { "not mapped" };
	private static final String[] CTC_EXPORT_KEYS = { "not mapped" };
	private static final String[] CTC_TRA_CHECKER_KEYS = { "not mapped" };
	private static final String[] CTC_TRA_ADJUSTER_KEYS = { "not mapped" };
	private static final String[] CTC_TRA_ADJUSTER_NQ_KEYS = { "not mapped" };
	//------------------------------------------------------------------------


	/** titles of this plug-in's menu items */
	private static final Map< String, String > menuTexts = new HashMap<>();
	static
	{
		menuTexts.put( CTC_IMPORT, "Import from CTC format" );
		menuTexts.put( CTC_EXPORT, "Export to CTC format" );
		menuTexts.put( CTC_TRA_CHECKER, "Review TRA annotation" );
		menuTexts.put( CTC_TRA_ADJUSTER, "Auto-adjust TRA annotation" );
	}
	@Override
	public Map< String, String > getMenuTexts() { return menuTexts; }

	@Override
	public List< ViewMenuBuilder.MenuItem > getMenuItems()
	{
		return Collections.singletonList( menu( "Plugins",
			menu( "Cell Tracking Challenge",
				item( CTC_IMPORT ),
				item( CTC_EXPORT ),
				item( CTC_TRA_CHECKER ),
				item( CTC_TRA_ADJUSTER )
			)
		) );
	}

	/** Command descriptions for all provided commands */
	@Plugin( type = Descriptions.class )
	public static class Descriptions extends CommandDescriptionProvider
	{
		public Descriptions()
		{
			super( KeyConfigScopes.MAMUT, KeyConfigContexts.TRACKSCHEME, KeyConfigContexts.BIGDATAVIEWER );
		}

		@Override
		public void getCommandDescriptions( final CommandDescriptions descriptions )
		{
			descriptions.add(CTC_IMPORT, CTC_IMPORT_KEYS, "");
			descriptions.add(CTC_EXPORT, CTC_EXPORT_KEYS, "");
			descriptions.add(CTC_TRA_CHECKER, CTC_TRA_CHECKER_KEYS, "");
			descriptions.add(CTC_TRA_ADJUSTER, CTC_TRA_ADJUSTER_KEYS, "");
			descriptions.add(CTC_TRA_ADJUSTER_NQ, CTC_TRA_ADJUSTER_NQ_KEYS, "");
		}
	}
	//------------------------------------------------------------------------


	private final AbstractNamedAction actionImport;
	private final AbstractNamedAction actionExport;
	private final AbstractNamedAction actionTRAreview;
	private final AbstractNamedAction actionTRAadjust;
	private final AbstractNamedAction actionTRAadjustNQ;

	/** reference to the currently available project in Mastodon */
	private ProjectModel projectModel;

	/** default c'tor: creates Actions available from this plug-in */
	public CTC_Plugins()
	{
		actionImport       = new RunnableAction( CTC_IMPORT, this::importer );
		actionExport       = new RunnableAction( CTC_EXPORT, this::exporter );
		actionTRAreview    = new RunnableAction( CTC_TRA_CHECKER, this::TRAreviewer );
		actionTRAadjust    = new RunnableAction( CTC_TRA_ADJUSTER, this::TRAadjuster );
		actionTRAadjustNQ  = new RunnableAction( CTC_TRA_ADJUSTER_NQ, this::TRAadjusterNQ );
		updateEnabledActions();
	}

	/** register the actions to the application (with no shortcut keys) */
	@Override
	public void installGlobalActions( final Actions actions )
	{
		actions.namedAction( actionImport,       CTC_IMPORT_KEYS );
		actions.namedAction( actionExport,       CTC_EXPORT_KEYS );
		actions.namedAction( actionTRAreview,    CTC_TRA_CHECKER_KEYS );
		actions.namedAction( actionTRAadjust,    CTC_TRA_ADJUSTER_KEYS );
		actions.namedAction( actionTRAadjustNQ , CTC_TRA_ADJUSTER_NQ_KEYS );
	}

	/** learn about the current project's params */
	@Override
	public void setAppPluginModel( final ProjectModel model )
	{
		//the application reports back to us if some project is available
		this.projectModel = model;
		updateEnabledActions();
	}

	/** enables/disables menu items based on the availability of some project */
	private void updateEnabledActions()
	{
		actionImport.setEnabled( projectModel != null );
		actionExport.setEnabled( projectModel != null );
		actionTRAreview.setEnabled( projectModel != null );
		actionTRAadjust.setEnabled( projectModel != null );
		actionTRAadjustNQ.setEnabled( projectModel != null );
	}
	//------------------------------------------------------------------------
	//------------------------------------------------------------------------

	/** opens the import dialog to find the tracks.txt file,
	    and runs the import on the currently viewed images
	    provided params were harvested successfully */
	private void importer()
	{
		this.getContext().getService(CommandService.class).run(
			ImporterPlugin.class, true,
			"projectModel", projectModel,
			"logService", this.getContext().getService(LogService.class));
	}

	/** opens the export dialog, and runs the export
	    provided params were harvested successfully */
	private void exporter()
	{
		this.getContext().getService(CommandService.class).run(
			ExporterPlugin.class, true,
			"outImgVoxelType", new UnsignedShortType(),
			"projectModel", projectModel,
			"logService", this.getContext().getService(LogService.class));
	}


	private void TRAreviewer()
	{
		this.getContext().getService(CommandService.class).run(
			TRAreviewPlugin.class, true,
			"projectModel", projectModel,
			"logService", this.getContext().getService(LogService.class));
	}

	private void TRAadjuster()
	{
		this.getContext().getService(CommandService.class).run(
			TRAadjustPlugin.class, true,
			"projectModel", projectModel,
			"logService", this.getContext().getService(LogService.class));
	}

	/** the same as TRAadjuster except that choices are hard-coded and
	    so the operation runs directly without poping up any dialog */
	private void TRAadjusterNQ()
	{
		//answers to what the questions would be...
		final double boxSize = 1.5;
		final boolean repeat = true;
		final int maxIters = 10;
		final double changeFactor = 1.0;

		//report the answers
		final LogService logService = this.getContext().getService(LogService.class);
		logService.info("Running spot position adjuster with these params: "
			+boxSize+", "+repeat+", "+maxIters+", "+changeFactor);

		//just do the job...
		this.getContext().getService(CommandService.class).run(
			TRAadjustPlugin.class, true,
			"projectModel", projectModel,
			"logService", logService,
			"boxSizeUM", boxSize,
			"repeatUntilNoChange", repeat,
			"safetyMaxIters", maxIters,
			"repeatBoxSizeFact", changeFactor,
			"reportStats", false);
	}
}
