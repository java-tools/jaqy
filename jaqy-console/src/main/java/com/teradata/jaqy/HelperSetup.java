/*
 * Copyright (c) 2017 Teradata
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.teradata.jaqy;

import com.teradata.jaqy.helper.PostgresHelperFactory;
import com.teradata.jaqy.helper.TeradataHelperFactory;

/**
 * @author	Heng Yuan
 */
class HelperSetup
{
	public static void init (Globals globals)
	{
		globals.getHelperManager ().addHelperFactory ("teradata", new TeradataHelperFactory ());
		globals.getHelperManager ().addHelperFactory ("postgresql", new PostgresHelperFactory ());
	}
}
