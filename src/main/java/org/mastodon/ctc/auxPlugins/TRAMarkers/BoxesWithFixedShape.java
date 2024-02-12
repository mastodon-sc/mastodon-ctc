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
package org.mastodon.ctc.auxPlugins.TRAMarkers;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import org.mastodon.ctc.auxPlugins.TRAMarkersProvider;

@Plugin( type = Command.class, visible = false,
         name = "Specify the full box size in the image units (e.g. in microns):" )
public class BoxesWithFixedShape implements TRAMarkersProvider.intersectionDecidable, Command
{
	@Parameter(visibility = ItemVisibility.MESSAGE, required = false, initializer = "setResHint")
	String resolutionMsg = "Unknown resolution of the image";
	//
	void setResHint()
	{ resolutionMsg = resolutionHint; }
	//
	@Parameter(visibility = ItemVisibility.INVISIBLE, required = false)
	String resolutionHint = "Unknown resolution of the image";

	@Parameter(min = "0", stepSize = "1")
	double xFullWidth = 0;

	@Parameter(min = "0", stepSize = "1")
	double yFullWidth = 0;

	@Parameter(min = "0", stepSize = "1")
	double zFullWidth = 0;

	//shortcut: width of half of the box
	double xHalfSize, yHalfSize, zHalfSize;

	@Override
	public void init()
	{
		xHalfSize = xFullWidth/2.0;
		yHalfSize = yFullWidth/2.0;
		zHalfSize = zFullWidth/2.0;
	}

	@Override
	public void setHalfBBoxInterval(final double[] halfBBoxSize, final double radius)
	{
		halfBBoxSize[0] = xHalfSize;
		halfBBoxSize[1] = yHalfSize;
		halfBBoxSize[2] = zHalfSize;
	}

	@Override
	public boolean isInside(final double[] distVec, final double radius)
	{
		if (Math.abs(distVec[0]) > xHalfSize) return false;
		if (Math.abs(distVec[1]) > yHalfSize) return false;
		if (Math.abs(distVec[2]) > zHalfSize) return false;

		//to prevent the full-even-sized boxes to have +1 size
		//(e.g., fullWidth=4 -> halfSize=2 -> would create 2+1+2 wide box)
		if (distVec[0] == xHalfSize) return false;
		if (distVec[1] == yHalfSize) return false;
		if (distVec[2] == zHalfSize) return false;
		return true;
	}

	@Override
	public String printInfo()
	{
		return "Box with fixed shape of "+xFullWidth+" x "+yFullWidth+" x "+zFullWidth;
	}

	@Override
	public void run() { /* intentionally empty */ }
}
