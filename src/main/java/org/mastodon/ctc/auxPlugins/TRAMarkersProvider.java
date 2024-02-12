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
package org.mastodon.ctc.auxPlugins;

import java.util.concurrent.ExecutionException;
import org.scijava.command.CommandService;
import mpicbg.spim.data.sequence.VoxelDimensions;
import org.mastodon.ctc.auxPlugins.TRAMarkers.*;

public class TRAMarkersProvider
{
	public
	interface intersectionDecidable
	{
		default void init() {}

		void setHalfBBoxInterval(final double[] halfBBoxSize, final double radius);
		boolean isInside(final double[] distVec, final double radius);

		default String printInfo() { return toString(); }
	}

	public static final
	String[] availableChoices = {
		"Spheres of spot-driven radii",
		"Spheres of fixed radius",
		"Boxes of fixed shape" };

	public static
	intersectionDecidable TRAMarkerFactory(final String choice, final VoxelDimensions pxSize, final CommandService cs)
	{
		intersectionDecidable markerShape;

		//sanity branch...
		if (choice == null || cs == null)
		{
			markerShape = new SpheresWithFloatingRadius();
			markerShape.init();
			return markerShape;
		}

		final String resHint =
			pxSize == null? "Unknown image resolution"
			: ( "Image resolution is: " +pxSize.dimension(0)
			                      +" x "+pxSize.dimension(1)
			                      +" x "+pxSize.dimension(2)
			                      +" "+pxSize.unit()+"/px" );

		//the main branch where 'choice' param is taken seriously...
		try
		{
			if (choice.startsWith("Boxes"))
				markerShape = (intersectionDecidable)cs.run(BoxesWithFixedShape.class,true,"resolutionHint",resHint).get().getCommand();
			else if (choice.contains("fixed"))
				markerShape = (intersectionDecidable)cs.run(SpheresWithFixedRadius.class,true,"resolutionHint",resHint).get().getCommand();
			else
				markerShape = new SpheresWithFloatingRadius();

			markerShape.init();
		}
		catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			return null;
		}

		return markerShape;
	}
}
