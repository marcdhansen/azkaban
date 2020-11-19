/*
 * Copyright 2020 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.imagemgmt.daos;

import azkaban.db.DatabaseOperator;
import azkaban.db.SQLTransaction;
import azkaban.imagemgmt.exeception.ImageMgmtDaoException;
import azkaban.imagemgmt.models.ImageOwnership;
import azkaban.imagemgmt.models.ImageType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.dbutils.ResultSetHandler;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO Implementation for accessing image types. This class contains implementation for creating
 * image type, getting image type metadata etc.
 */
@Singleton
public class ImageTypeDaoImpl implements ImageTypeDao {
  private static final Logger log = LoggerFactory.getLogger(ImageTypeDaoImpl.class);

  private final DatabaseOperator databaseOperator;

  static String INSERT_IMAGE_TYPE =
      "insert into image_types ( name, description, active, deployable, created_by, modified_by )"
          + " values (?, ?, ?, ?, ?, ?)";
  static String INSERT_IMAGE_OWNERSHIP =
      "insert into image_ownerships ( type_id, owner, role, created_by, modified_by ) "
          + "values (?, ?, ?, ?, ?)";

  @Inject
  public ImageTypeDaoImpl(DatabaseOperator databaseOperator) {
    this.databaseOperator = databaseOperator;
  }

  @Override
  public int createImageType(ImageType imageType) {
    final SQLTransaction<Integer> insertAndGetSpaceId = transOperator -> {
      // insert image type record
      transOperator.update(INSERT_IMAGE_TYPE, imageType.getName(), imageType.getDescription(),
          true, imageType.getDeployable().getName(), imageType.getCreatedBy(), imageType.getCreatedBy());
      int imageTypeId = Long.valueOf(transOperator.getLastInsertId()).intValue();
      // Insert ownerships record if present
      if( imageType.getOwnerships() != null && imageType.getOwnerships().size() > 0) {
        for(ImageOwnership imageOwnership : imageType.getOwnerships()) {
          transOperator.update(INSERT_IMAGE_OWNERSHIP, imageTypeId, imageOwnership.getOwner(),
              imageOwnership.getRole().getName(), imageType.getCreatedBy(), imageType.getCreatedBy());
        }
      }
      transOperator.getConnection().commit();
      return imageTypeId;
    };

    int imageTypeId = 0;
    try {
      /* what will happen if there is a partial failure in
         any of the below statements?
         Ideally all should happen in a transaction */
      imageTypeId = databaseOperator.transaction(insertAndGetSpaceId);
      log.info("Created image type id :"+imageTypeId);
    } catch (SQLException e) {
      log.error("Unable to create the image type metadata", e);
      throw new ImageMgmtDaoException("Exception occurred while creating image type metadata");
    }
    return imageTypeId;
  }

  @Override
  public Optional<ImageType> getImageTypeByName(String name) {
    final FetchImageTypeHandler fetchImageTypeHandler = new FetchImageTypeHandler();
    List<ImageType> imageTypes = new ArrayList<>();
    try {
      imageTypes = this.databaseOperator
          .query(FetchImageTypeHandler.FETCH_IMAGE_TYPE_BY_NAME, fetchImageTypeHandler, name);
      //
      if(imageTypes != null && imageTypes.size() > 1) {
        throw new ImageMgmtDaoException("Can't have more that one image type record for a given "
            + "type with name : "+name);
      }
    } catch (final SQLException ex) {
      log.error(FetchImageTypeHandler.FETCH_IMAGE_TYPE_BY_NAME + " failed.", ex);
      throw new ImageMgmtDaoException("Unable to fetch image type metadata from image type : "+name);
    }
    return imageTypes.isEmpty() ? Optional.empty() : Optional.of(imageTypes.get(0));
  }

  /**
   * ResultSetHandler implementation class for fetching image type
   */
  public static class FetchImageTypeHandler implements ResultSetHandler<List<ImageType>> {
    private static final String FETCH_IMAGE_TYPE_BY_ID =
        "SELECT id, name, description, active, deployable, created_on, created_by, modified_on, "
            + "modified_by FROM image_types WHERE id = ?";
    private static final String FETCH_IMAGE_TYPE_BY_NAME =
        "SELECT id, name, description, active, deployable, created_on, created_by, modified_on, "
            + "modified_by FROM image_types WHERE name = ?";
    private static final String FETCH_ALL_IMAGE_TYPES =
        "SELECT id, name, description, active, deployable, created_on, created_by, modified_on, "
            + "modified_by FROM image_types where active = ?";
    @Override
    public List<ImageType> handle(final ResultSet rs) throws SQLException {
      if (!rs.next()) {
        return Collections.emptyList();
      }
      final List<ImageType> imageTypes = new ArrayList<>();
      do {
        final int id = rs.getInt("id");
        final String name = rs.getString("name");
        final String description = rs.getString("description");
        final boolean active = rs.getBoolean("active");
        final String createdOn = rs.getString("created_on");
        final String createdBy = rs.getString("created_by");
        final String modifiedOn = rs.getString("modified_on");
        final String modifiedBy = rs.getString("modified_by");
        final ImageType imageType = new ImageType();
        imageType.setId(id);
        imageType.setName(name);
        imageType.setDescription(description);
        imageType.setCreatedOn(createdOn);
        imageType.setCreatedBy(createdBy);
        imageType.setModifiedOn(modifiedOn);
        imageType.setModifiedBy(modifiedBy);
        imageTypes.add(imageType);
      } while (rs.next());
      return imageTypes;
    }
  }
}