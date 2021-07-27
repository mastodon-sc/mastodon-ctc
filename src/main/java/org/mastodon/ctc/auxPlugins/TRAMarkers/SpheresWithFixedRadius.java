/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2019, Vladimír Ulman
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.mastodon.ctc.auxPlugins.TRAMarkers;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import org.mastodon.ctc.auxPlugins.TRAMarkersProvider;

@Plugin( type = Command.class, visible = false,
         name = "Specify the sphere radius in the image units (e.g. in microns):" )
public class SpheresWithFixedRadius implements TRAMarkersProvider.intersectionDecidable, Command
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
	double fixedRadius = 0;

	//shortcut
	double fixedRadiusSq;

	@Override
	public void init()
	{
		fixedRadiusSq = fixedRadius*fixedRadius;
	}

	@Override
	public void setHalfBBoxInterval(final double[] halfBBoxSize, final double radius)
	{
		halfBBoxSize[0] = fixedRadius;
		halfBBoxSize[1] = fixedRadius;
		halfBBoxSize[2] = fixedRadius;
	}

	@Override
	public boolean isInside(final double[] distVec, final double radius)
	{
		final double lenSq = (distVec[0] * distVec[0]) + (distVec[1] * distVec[1]) + (distVec[2] * distVec[2]);
		return lenSq <= fixedRadiusSq;
	}

	@Override
	public String printInfo()
	{
		return "Sphere with fixed radius of "+fixedRadius;
	}

	@Override
	public void run() { /* intentionally empty */ }
}
