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

import org.scijava.plugin.Plugin;
import org.scijava.plugin.Parameter;
import org.scijava.command.Command;
import org.scijava.widget.FileWidget;

import java.io.File;

@Plugin( type = Command.class, name = "Provide images location and filename template" )
public class FileTemplateProvider implements Command
{
	@Parameter(label = "Folder with images:", style = FileWidget.DIRECTORY_STYLE)
	File containingFolder;

	@Parameter(label = "Template for file names:",
	           description = "Use %d or %04d in the template to denote where numbers or 4-digits-zero-padded numbers will appear.")
	String filenameTemplate = "img%03d.tif";

	@Parameter(label = "Lineage TXT file name:")
	String filenameTXT = "res_track.txt";

	@Override
	public void run() { /* intentionally empty */ }
}
