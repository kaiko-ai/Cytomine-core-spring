package be.cytomine.repository;

/*
* Copyright (c) 2009-2022. Authors: see NOTICE file.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import be.cytomine.domain.CytomineDomain;
import be.cytomine.domain.image.ImageInstance;
import be.cytomine.domain.image.SliceInstance;
import be.cytomine.domain.ontology.AnnotationDomain;
import be.cytomine.domain.ontology.Term;
import be.cytomine.domain.ontology.Track;
import be.cytomine.domain.project.Project;
import be.cytomine.domain.security.SecUser;
import be.cytomine.exceptions.ObjectNotFoundException;
import be.cytomine.exceptions.WrongArgumentException;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.EntityManager;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
public abstract class AnnotationListing {

    /**
     *  default property group to show
     */
    public static final List<String> availableColumnsDefault = List.of("basic", "meta", "term");

    /**
     *  all properties group available, each value is a list of assoc [propertyName, SQL columnName/methodName)
     *  If value start with #, don't use SQL column, its a "trensiant property"
     */
    abstract LinkedHashMap<String, AvailableColumns> getAvailableColumn();

    protected EntityManager entityManager;

    List<String> columnsToPrint;

    Long project = null;
    Long image = null;
    List<Long> images = null;

    Long slice = null;
    List<Long> slices = null;

    Long track = null;
    List<Long> tracks = null;
    Long beforeSlice = null;
    Long afterSlice = null;
    Long sliceDimension = null;

    Long user = null;
    Long userForTermAlgo = null;
    List<Long> usersForTermAlgo = null;

    Long term = null;
    List<Long> terms = null;

    Long suggestedTerm = null;
    List<Long> suggestedTerms = null;

    List<Long> users = null;//for user that draw annotation
    List<Long> usersForTerm = null;//for user that add a term to annotation

    List<Long> reviewUsers;

    Long tag = null;
    List<Long> tags = null;

    Date afterThan = null;
    Date beforeThan = null;

    Boolean notReviewedOnly = false;
    Boolean noTerm = false;
    Boolean noTag = false;
    Boolean noAlgoTerm = false;
    Boolean multipleTerm = false;
    Boolean noTrack = false;
    Boolean multipleTrack = false;

    String bbox = null;
    String bboxAnnotation = null;

    Object baseAnnotation = null;
    Long maxDistanceBaseAnnotation = null;


    List<Long> parents;

    //not used for search critera (just for specific request
    Boolean avoidEmptyCentroid = false;
    Long excludedAnnotation = null;

    Boolean kmeans = false;
    Integer kmeansValue = 3;

    abstract String getFrom(Map<String, Object> parameters);

    public abstract String getDomainClass();

    abstract String buildExtraRequest(Map<String, Object> parameters);

    LinkedHashMap<String,String> extraColmun = new LinkedHashMap<>();

    LinkedHashMap<String,String> orderBy = new LinkedHashMap<>();

    public AnnotationListing(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void addExtraColumn(String propName, String column) {
        extraColmun.put(propName, column);
    }

    /**
     * Get all properties name available
     * If group argument is provieded, just get properties from these groups
     */
    public List<String> getAllPropertiesName() {
        return getAllPropertiesName(new ArrayList<>(getAvailableColumn().keySet()));
    }

    public List<String> getAllPropertiesName(List<String> groups) {
        List<String> propNames = new ArrayList<>();
        for (String groupName : groups) {
            for (Map.Entry<String, String> assoc : getAvailableColumn().get(groupName).entrySet()) {
                propNames.add(assoc.getKey());
            }
        }
        return propNames;
    }

    /**
     * Get all properties to print
     */
    Map<String, String> buildColumnToPrint() {
        if (columnsToPrint==null) {
            columnsToPrint = new ArrayList<>(availableColumnsDefault);
        }
        columnsToPrint.add("basic"); //mandatory to have id
        columnsToPrint = columnsToPrint.stream().distinct().collect(Collectors.toList());

        Map<String, String> columns = new LinkedHashMap<>();

        for (Map.Entry<String, AvailableColumns> entry : getAvailableColumn().entrySet()) {
            if (columnsToPrint.contains(entry.getKey())) {
                columns.putAll(entry.getValue());
            }
        }
        columns.putAll(extraColmun);
        return columns;
    }

    /**
     * Get container for security check
     */
    public CytomineDomain container() {
        if (project!=null) {
            return entityManager.find(Project.class, project);
        } else if (image!=null) {
            return entityManager.find(ImageInstance.class, image).container();
        } else if (images!=null) {
            List<Project> projectList = new ArrayList<>();
            for (Long idImage : images) {
                projectList.add((Project)entityManager.find(ImageInstance.class, idImage).getProject());
            }
            projectList = projectList.stream().distinct().collect(Collectors.toList());
            if (projectList.size() > 1) {
                throw new WrongArgumentException("Images from filter must all be from the same project!");
            }
            return projectList.stream().findFirst().get();
        } else if (slice!=null) {
            return entityManager.find(SliceInstance.class, slice).container();
        } else if (slices!=null) {
            List<Project> projectList = new ArrayList<>();
            for (Long idImage : slices) {
                projectList.add((Project)entityManager.find(SliceInstance.class, idImage).getProject());
            }
            projectList = projectList.stream().distinct().collect(Collectors.toList());
            if (projectList.size() > 1) {
                throw new WrongArgumentException("Slices from filter must all be from the same project!");
            }
            return projectList.stream().findFirst().get();
        }
        throw new WrongArgumentException("There is no project or image or slice filter. We cannot check acl!");
    }

    /**
     * Generate SQL request string
     */
    public String getAnnotationsRequest(Map<String, Object> parameters) {

        buildExtraRequest(parameters);

        Map<String, String> columns = buildColumnToPrint();
        Map<String, String> sqlColumns = new LinkedHashMap<>();
        Map<String, String>  postComputedColumns = new LinkedHashMap<>();

        for (Map.Entry<String, String> colum : columns.entrySet()) {
            if (!colum.getValue().startsWith("#")) {
                sqlColumns.put(colum.getKey(), colum.getValue());
            } else {
                postComputedColumns.put(colum.getKey(), colum.getValue());
            }
        }
        String whereRequest =
                getProjectConst(parameters) +
                        getUserConst(parameters) +
                        getUsersConst(parameters) +

                        getImageConst(parameters) +
                        getImagesConst(parameters) +

                        getSliceConst(parameters) +
                        getSlicesConst(parameters) +

                        getTagConst(parameters) +
                        getTagsConst(parameters) +

                        getTermConst(parameters) +
                        getTermsConst(parameters) +

                        getTrackConst(parameters) +
                        getTracksConst(parameters) +
                        getBeforeOrAfterSliceConst(parameters) +

                        getUsersForTermConst(parameters) +

                        getUserForTermAlgoConst(parameters) +
                        getUsersForTermAlgoConst(parameters) +

                        getSuggestedTermConst(parameters) +
                        getSuggestedTermsConst(parameters) +

                        getNotReviewedOnlyConst() +
                        getParentsConst(parameters) +
                        getAvoidEmptyCentroidConst() +
                        getReviewUsersConst(parameters) +

                        getIntersectConst(parameters) +
                        getIntersectAnnotationConst(parameters) +
                        getMaxDistanceAnnotationConst(parameters) +
                        getExcludedAnnotationConst(parameters) +

                        getBeforeThan(parameters) +
                        getAfterThan(parameters) +
                        createOrderBy();

        if (term!=null || terms!=null || track!=null || tracks!=null) {
            String request = "SELECT DISTINCT a.*, ";

            if (term!=null || terms!=null) {
                sqlColumns.remove("term");
                sqlColumns.remove("annotationTerms");
                sqlColumns.remove("userTerm");

                if (this instanceof  AlgoAnnotationListing) {
                    request += "aat.term_id as term, aat.id as annotationTerms, aat.user_job_id as userTerm ";
                }
                else if (this instanceof ReviewedAnnotationListing) {
                    request += "at.term_id as term, 0 as annotationTerms, a.user as userTerm ";
                }
                else {
                    request += "at.term_id as term, at.id as annotationTerms, at.user_id as userTerm ";
                }

            }

            if ((term!=null || terms!=null) && (track!=null || tracks!=null)) {
                request += ", ";
            }

            if (track!=null || tracks!=null) {
                sqlColumns.remove("track");
                sqlColumns.remove("annotationTracks");
                request += "atr.track_id as track, atr.id as annotationTracks ";
            }

            request += "FROM (" + getSelect(sqlColumns) + getFrom(parameters) + whereRequest + ") a \n";

            if (term!=null || terms!=null) {
                if (this instanceof AlgoAnnotationListing) {
                    request += "LEFT OUTER JOIN algo_annotation_term aat ON aat.annotation_ident = a.id ";
                }
                else if (this instanceof ReviewedAnnotationListing) {
                    request += "LEFT OUTER JOIN reviewed_annotation_term at ON a.id = at.reviewed_annotation_terms_id ";
                }
                else {
                    request += "LEFT OUTER JOIN annotation_term at ON a.id = at.user_annotation_id ";
                }
            }


            if (track!=null || tracks!=null)
                request += "LEFT OUTER JOIN annotation_track atr ON a.id = atr.annotation_ident ";

            request += "WHERE true ";
            if (term!=null || terms!=null) {
                if (this instanceof AlgoAnnotationListing) {
                    request += "AND aat.deleted IS NULL ";
                }
                else if (!(this instanceof ReviewedAnnotationListing)) {
                    request += "AND at.deleted IS NULL ";
                }
            }

            request += "ORDER BY ";
            request += (track!=null || tracks!=null) ? "a.rank asc" : "a.id desc ";
            if (term!=null || terms!=null) {
                if (this instanceof AlgoAnnotationListing) {
                    request += ", aat.term_id ";
                }
                else {
                    request += ", at.term_id ";
                }
            }
            request += ((track!=null || tracks!=null) ? ", atr.track_id " : "");
            return request;
        }

        return getSelect(sqlColumns) + getFrom(parameters) + whereRequest;

    }

    /**
     * Generate SQL string for SELECT with only asked properties
     */
    String getSelect(Map<String,String> columns) {
        if (kmeansValue >= 3) {
            List<String> requestHeadList = new ArrayList<>();
            for (Map.Entry<String, String> entry : columns.entrySet()) {
                if (entry.getKey().equals("term") && !(this instanceof ReviewedAnnotationListing)) {
                    String table ="";
                    if(entry.getValue().contains("aat")) {
                        table = "aat";
                    } else if(entry.getValue().contains("at")) {
                        table = "at";
                    }
                    requestHeadList.add("CASE WHEN "+table+".deleted IS NOT NULL THEN NULL ELSE "+entry.getValue()+" END as " + entry.getKey());
                } else {
                    requestHeadList.add(entry.getValue() + " as " + entry.getKey());
                }
            }
            if (track!=null || tracks!=null) {
                requestHeadList.add("(asl.channel + ai.channels * (asl.z_stack + ai.depth * asl.time)) as rank");
            }
            return "SELECT " + String.join(", ", requestHeadList) + " \n";
        } else {
            return "SELECT ST_ClusterKMeans(location, 5) OVER () AS kmeans, location\n";
        }

    }
    /**
     * Add property group to show if use in where constraint.
     * E.g: if const with term_id = x, we need to make a join on annotation_term.
     * So its mandatory to add "term" group properties (even if not asked)
     */
    void addIfMissingColumn(String column) {
        if (!columnsToPrint.contains(column)) {
            columnsToPrint.add(column);
        }
    }

    String joinValues(List list) {
        return (String)list.stream().map(x -> String.valueOf(x)).collect(Collectors.joining(", "));
    }

    String getProjectConst(Map<String, Object> parameters) {
        if(project!=null) {
            parameters.put("project_id", project);
            return "AND a.project_id = :project_id\n";
        }
        return "";
    }

    String getUsersConst(Map<String, Object> parameters) {
        if(users!=null) {
            parameters.put("users", users);
            return "AND a.user_id IN :users\n";
        }
        return "";
    }

    String getReviewUsersConst(Map<String, Object> parameters) {
        if(reviewUsers!=null) {
            parameters.put("reviewUsers", reviewUsers);
            return "AND a.review_user_id IN :reviewUsers\n";
        }
        return "";
    }


    String getUsersForTermConst(Map<String, Object> parameters) {
        if (usersForTerm!=null) {
            addIfMissingColumn("term");
            parameters.put("usersForTerm", usersForTerm);
            return "AND at.user_id IN :usersForTerm\n";
        } else {
            return "";
        }
    }

    String getImagesConst(Map<String, Object> parameters) {
        if (images!=null && project!=null && images.size() == entityManager.find(Project.class, project).getCountImages()) {
            return ""; //images number equals to project image number, no const needed
        } else if (images!=null && images.isEmpty()) {
            throw new ObjectNotFoundException("The image has been deleted!");
        } else {
            if(images!=null) {
                parameters.put("images", images);
                return "AND a.image_id IN :images\n";
            }
            return "";
        }

    }

    String getImageConst(Map<String, Object> parameters) {
        if (image!=null) {
            ImageInstance imageInstance = entityManager.find(ImageInstance.class, image);
            if (imageInstance==null) {
                throw new ObjectNotFoundException("Image " + image + " not exist!");
            }
            parameters.put("image_id", imageInstance.getId());
            return "AND a.image_id = :image_id \n";
        } else {
            return "";
        }
    }

    String getSlicesConst(Map<String, Object> parameters) {
        if (slices!=null && slices.isEmpty()) {
            throw new ObjectNotFoundException("The slice has been deleted!");
        } else {
            if(slices!=null) {
                parameters.put("slices", slices);
                return "AND a.slice_id IN :slices\n";
            }
            return "";
        }

    }

    String getSliceConst(Map<String, Object> parameters) {
        if (slice!=null) {
            if (entityManager.find(SliceInstance.class, slice)==null) {
                throw new ObjectNotFoundException("Slice "+slice+" not exist!");
            }
            parameters.put("slice_id", slice);
            return "AND a.slice_id = :slice_id\n";
        } else {
            return "";
        }
    }

    String getUserConst(Map<String, Object> parameters) {
        if (user!=null) {
            if (entityManager.find(SecUser.class, user)==null) {
                throw new ObjectNotFoundException("User "+user+" not exist!");
            }
            parameters.put("user_id", user);
            return "AND a.user_id = :user_id \n";
        } else {
            return "";
        }
    }

    abstract String getNotReviewedOnlyConst();

    String getIntersectConst(Map<String, Object> parameters) {
        if (bbox!=null) {
            parameters.put("bbox", bbox.toString());
            return "AND ST_Intersects(a.location,ST_GeometryFromText(:bbox,0))\n";
        } else {
            return "";
        }
    }

    String getIntersectAnnotationConst(Map<String, Object> parameters) {
        if (bboxAnnotation!=null) {
            parameters.put("bboxAnnotation", bboxAnnotation.toString());
            return "AND ST_Intersects(a.location,ST_GeometryFromText(:bboxAnnotation,0))\n";
        } else {
            return "";
        }
    }

    String getMaxDistanceAnnotationConst(Map<String, Object> parameters) {
        if(maxDistanceBaseAnnotation!=null) {
            if(baseAnnotation==null) {
                throw new ObjectNotFoundException("You need to provide a 'baseAnnotation' parameter (annotation id/location = "+baseAnnotation+")!");
            } else {
                parameters.put("maxDistanceBaseAnnotation", maxDistanceBaseAnnotation);
                try {
                    AnnotationDomain base = AnnotationDomain.getAnnotationDomain(entityManager, ((Long)baseAnnotation), null);
                    //ST_distance(a.location,ST_GeometryFromText('POINT (0 0)'))
                    parameters.put("getWktLocation", base.getWktLocation());
                    return "AND ST_distance(a.location,ST_GeometryFromText(:getWktLocation)) <= :maxDistanceBaseAnnotation\n";
                } catch (Exception e) {
                    parameters.put("baseAnnotation", baseAnnotation);
                    return "AND ST_distance(a.location,ST_GeometryFromText(:baseAnnotation)) <= :maxDistanceBaseAnnotation\n";
                }
            }
        } else {
            return "";
        }
    }

    String getAvoidEmptyCentroidConst() {
        return (avoidEmptyCentroid ? "AND ST_IsEmpty(st_centroid(a.location))=false\n" : "");
    }

    String getTermConst(Map<String, Object> parameters) {
        if (term!=null) {
            if (entityManager.find(Term.class, term)==null) {
                throw new ObjectNotFoundException("Term " + term + "not exist!");
            }
            addIfMissingColumn("term");
            parameters.put("term_id", term);

            if (this instanceof ReviewedAnnotationListing)
                return " AND (at.term_id = :term_id" + ((noTerm) ? " OR at.term_id IS NULL" : "") + ")\n";
            else
                return " AND ((at.term_id = :term_id)" + ((noTerm) ? " OR at.term_id IS NULL" : "") + ")\n";
        } else {
            return "";
        }
    }
    String getParentsConst(Map<String, Object> parameters) {
        if (parents!=null) {
            parameters.put("parents", parents);
            return " AND a.parent_ident IN parents\n";
        } else {
            return "";
        }
    }


    String getTermsConst(Map<String, Object> parameters) {
        if (terms!=null) {
            addIfMissingColumn("term");
            parameters.put("terms", terms);
            if (this instanceof ReviewedAnnotationListing) {
                return " AND (at.term_id IN (:terms)" + ((noTerm) ? " OR at.term_id IS NULL" : "") + ")\n";
            } else {
                return " AND ((at.term_id IN (:terms))" + ((noTerm) ? " OR at.term_id IS NULL" : "") + ")\n";
            }
        } else {
            return "";
        }
    }

    String getTrackConst(Map<String, Object> parameters) {
        if (track!=null) {
            if (entityManager.find(Track.class, track)!=null) {
                throw new ObjectNotFoundException("Track " + track + " not exists !");
            }
            addIfMissingColumn("track");
            parameters.put("track_id", track);
            return " AND (atr.track_id = :track_id" + ((noTrack) ? " OR atr.track_id IS NULL" : "") + ")\n";
        } else {
            return "";
        }
    }

    String getTracksConst(Map<String, Object> parameters) {
        if (tracks!=null) {
            addIfMissingColumn("track");
            parameters.put("tracks", tracks);
            return "AND (atr.track_id IN :tracks " + ((noTrack) ? " OR atr.track_id IS NULL" : "") + ")\n";
        } else {
            return "";
        }
    }

    String getTagConst(Map<String, Object> parameters) {
        if (tag!=null && noTag) {
            parameters.put("tag_id", tag);
            return "AND (tda.tag_id = :tag_id OR tda.tag_id IS NULL)\n";
        } else if (tag!=null) {
            parameters.put("tag_id", tag);
            return "AND tda.tag_id = :tag_id\n";
        } else {
            return "";
        }
    }

    String getTagsConst(Map<String, Object> parameters) {
        if (tags!=null  && noTag) {
            parameters.put("tags", tags);
            return "AND (tda.tag_id IN :tags OR tda.tag_id IS NULL)\n";
        } else if (tags!=null ) {
            parameters.put("tags", tags);
            return "AND tda.tag_id IN :tags)\n";
        } else {
            return "";
        }
    }


    String getBeforeOrAfterSliceConst(Map<String, Object> parameters) {
        if ((track!=null || tracks!=null) && (beforeSlice!=null || afterSlice!=null)) {
            addIfMissingColumn("slice");
            Long sliceId = (beforeSlice!=null) ? beforeSlice : afterSlice;
            SliceInstance sliceInstance = entityManager.find(SliceInstance.class, sliceId);
            if (sliceInstance==null) {
                throw new ObjectNotFoundException("Slice "+ sliceId +" not exists !");
            }
            String sign = (beforeSlice!=null) ? "<" : ">";
            parameters.put("slideInstanceRank", sliceInstance.getBaseSlice().getRank());
            return "AND (asl.channel + ai.channels * (asl.z_stack + ai.depth * asl.time)) "+sign+" :slideInstanceRank\n";
        } else {
            return "";
        }
    }

    String getExcludedAnnotationConst(Map<String, Object> parameters) {
        if(excludedAnnotation!=null) {
            parameters.put("excludedAnnotation", excludedAnnotation);
            return "AND a.id <> :excludedAnnotation\n";
        } else {
            return "";
        }
    }

    String getSuggestedTermConst(Map<String, Object> parameters) {
        if (suggestedTerm!=null) {
            if (entityManager.find(Term.class, suggestedTerm)!=null) {
                throw new ObjectNotFoundException("Term "+suggestedTerm+" not exist!");
            }
            addIfMissingColumn("algo");
            parameters.put("suggestedTerm", suggestedTerm);
            return "AND aat.term_id = :suggestedTerm AND aat.deleted IS NULL \n";
        } else {
            return "";
        }
    }

    String getSuggestedTermsConst(Map<String, Object> parameters) {
        if (suggestedTerms!=null) {
            addIfMissingColumn("algo");
            parameters.put("suggestedTerms", suggestedTerms);
            return "AND aat.term_id IN :suggestedTerms\n";
        } else {
            return "";
        }
    }

    String getUserForTermAlgoConst(Map<String, Object> parameters) {
        if (userForTermAlgo!=null) {
            addIfMissingColumn("term");
            addIfMissingColumn("algo");
            parameters.put("userForTermAlgo", userForTermAlgo);
            return "AND aat.user_job_id = :userForTermAlgo\n";
        } else {
            return "";
        }
    }

    String getUsersForTermAlgoConst(Map<String, Object> parameters) {
        if (usersForTermAlgo!=null) {
            addIfMissingColumn("algo");
            addIfMissingColumn("term");
            parameters.put("usersForTermAlgo", usersForTermAlgo);
            return "AND aat.user_job_id IN :usersForTermAlgo\n";
        } else {
            return "";
        }
    }

    abstract String createOrderBy();

    String getBeforeThan(Map<String, Object> parameters) {
        if (beforeThan!=null) {
            parameters.put("beforeThan", beforeThan);
            return "AND a.created < :beforeThan\n";
        } else {
            return "";
        }
    }
    String getAfterThan(Map<String, Object> parameters) {
        if (afterThan!=null) {
            parameters.put("afterThan", afterThan);
            return "AND a.created > :afterThan\n";
        } else {
            return "";
        }
    }
}


