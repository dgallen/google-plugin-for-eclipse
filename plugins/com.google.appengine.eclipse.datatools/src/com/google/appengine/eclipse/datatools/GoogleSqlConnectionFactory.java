/*******************************************************************************
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.google.appengine.eclipse.datatools;

import org.eclipse.datatools.connectivity.IConnection;
import org.eclipse.datatools.connectivity.IConnectionFactory;
import org.eclipse.datatools.connectivity.IConnectionProfile;

/**
 * The Google Cloud SQL connection factory.
 */
public class GoogleSqlConnectionFactory implements IConnectionFactory {

  public GoogleSqlConnectionFactory() {
    super();
  }

  @Override
  public IConnection createConnection(IConnectionProfile profile) {
    GoogleSqlConnection connection = new GoogleSqlConnection(profile,
        getClass());
    connection.open();
    return connection;
  }

  @Override
  public IConnection createConnection(IConnectionProfile profile, String uid,
      String pwd) {
    return createConnection(profile);
  }
}