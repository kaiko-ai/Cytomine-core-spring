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

import be.cytomine.domain.image.ImageInstance;
import be.cytomine.exceptions.WrongArgumentException;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

import javax.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class ReviewedAnnotationListing extends AnnotationListing {

    public ReviewedAnnotationListing(EntityManager entityManager) {
        super(entityManager);
    }

    public String getDomainClass() {
        return "be.cytomine.domain.ontology.ReviewedAnnotation";
    }

    @Override
    LinkedHashMap<String, AvailableColumns> getAvailableColumn() {
        LinkedHashMap<String, AvailableColumns> map = new LinkedHashMap<>();

        AvailableColumns basic = new AvailableColumns();
        basic.put("id", "a.id");
        map.put("basic", basic);

        AvailableColumns meta = new AvailableColumns();
        meta.put("created", "extract(epoch from a.created)*1000");
        meta.put("updated", "extract(epoch from a.updated)*1000");
        meta.put("image", "a.image_id");
        meta.put("slice", "a.slice_id");
        meta.put("project", "a.project_id");
        meta.put("user", "a.user_id");
        meta.put("nbComments", "a.count_comments");
        meta.put("reviewed", "'true'");
        meta.put("reviewUser", "a.review_user_id");
        meta.put("parentIdent", "parent_ident");
        meta.put("cropURL", "#cropURL");
        meta.put("smallCropURL", "#smallCropURL");
        meta.put("url", "#url");
        meta.put("imageURL", "#imageURL");
        map.put("meta", meta);


        AvailableColumns wkt = new AvailableColumns();
        wkt.put("location", "a.wkt_location");
        wkt.put("geometryCompression", "a.geometry_compression");
        map.put("wkt", wkt);


        AvailableColumns gis = new AvailableColumns();
        gis.put("area", "area");
        gis.put("areaUnit", "area_unit");
        gis.put("perimeter", "perimeter");
        gis.put("perimeterUnit", "perimeter_unit");
        gis.put("x", "ST_X(ST_centroid(a.location))");
        gis.put("y", "ST_Y(ST_centroid(a.location))");
        map.put("gis", gis);


        AvailableColumns term = new AvailableColumns();
        term.put("term", "at.term_id");
        term.put("annotationTerms", "0");
        term.put("userTerm", "a.user_id"); //user who add the term, is the user that create reviewedannotation (a.user_id)
        map.put("term", term);

        AvailableColumns image = new AvailableColumns();
        image.put("originalFilename", "ai.original_filename");
        image.put("instanceFilename", "COALESCE(ii.instance_filename, ai.original_filename)");
        map.put("image", image);

        AvailableColumns slice = new AvailableColumns();
        slice.put("channel", "asl.channel");
        slice.put("zStack", "asl.z_stack");
        slice.put("time", "asl.time");
        map.put("slice", slice);


        AvailableColumns algo = new AvailableColumns();
        algo.put("id", "aat.id");
        algo.put("rate", "aat.rate");
        map.put("algo", algo);

        AvailableColumns user = new AvailableColumns();
        user.put("creator", "u.username");
        user.put("lastname", "u.lastname");
        user.put("firstname", "u.firstname");
        map.put("user", user);

        return map;
    }

    /**
     * Generate SQL string for FROM
     * FROM depends on data to print (if image name is aksed, need to join with imageinstance+abstractimage,...)
     */
    String getFrom(Map<String, Object> parameters) {
        String from = "FROM reviewed_annotation a ";
        String where = "WHERE true\n";

        if (tags != null) {
            from += " LEFT OUTER JOIN tag_domain_association tda ON a.id = tda.domain_ident AND tda.domain_class_name = '" + getDomainClass() + "' ";
        }

        if (multipleTerm) {
            from += "LEFT OUTER JOIN reviewed_annotation_term at ON a.id = at.reviewed_annotation_terms_id ";
            from += "LEFT OUTER JOIN reviewed_annotation_term at2 ON a.id = at2.reviewed_annotation_terms_id ";
            where += "AND at.term_id <> at2.term_id  ";
            /*from = "$from, reviewed_annotation_term at, reviewed_annotation_term at2 "
            where = "$where" +
                    "AND a.id = at.reviewed_annotation_terms_id\n" +
                    " AND a.id = at2.reviewed_annotation_terms_id\n" +
                    " AND at.term_id <> at2.term_id \n"*/
        } else if (noTerm && !(term != null || terms != null)) {
            from = from + " LEFT OUTER JOIN reviewed_annotation_term at ON a.id = at.reviewed_annotation_terms_id ";
            where = where + " AND at.reviewed_annotation_terms_id IS NULL \n";
        } else if (columnsToPrint.contains("term")) {
            from = from + " LEFT OUTER JOIN reviewed_annotation_term at ON a.id = at.reviewed_annotation_terms_id ";
        }

        if (columnsToPrint.contains("image")) {
            from += "INNER JOIN image_instance ii ON a.image_id = ii.id INNER JOIN abstract_image ai ON ii.base_image_id = ai.id ";
        }

        if (columnsToPrint.contains("slice")) {
            from += "INNER JOIN slice_instance si ON a.slice_id = si.id INNER JOIN abstract_slice asl ON si.base_slice_id = asl.id ";
        }

        if (columnsToPrint.contains("user")) {
            from += "INNER JOIN sec_user u ON a.user_id = u.id ";
        }

        return from + "\n" + where;
    }

    @Override
    String getUsersForTermConst(Map<String, Object> parameters) {
        return "";
    }

    String buildExtraRequest(Map<String, Object> parameters) {

        if (kmeansValue == 3 && image != null && bbox != null) {
            /**
             * We will sort annotation so that big annotation that covers a lot of annotation comes first (appear behind little annotation so we can select annotation behind other)
             * We compute in 'gc' the set of all other annotation that must be list
             * For each review annotation, we compute the number of other annotation that cover it (ST_CoveredBy => t or f => 0 or 1)
             *
             * ST_CoveredBy will return false if the annotation is not perfectly "under" the compare annotation (if some points are outside)
             * So in gc, we increase the size of each compare annotation just for the check
             * So if an annotation x is under y but x has some point next outside y, x will appear top (if no resize, it will appear top or behind).
             */
            String xfactor = "1.28";
            String yfactor = "1.28";
            ImageInstance imageInstance = entityManager.find(ImageInstance.class, image);
            //TODO:: get zoom info from UI client, display with scaling only with hight zoom (< annotations)

            double imageWidth = imageInstance.getBaseImage().getWidth();
            Geometry bboxLocal = null;
            try {
                bboxLocal = new WKTReader().read(bbox);
            } catch (ParseException e) {
                throw new WrongArgumentException("Annotation " + bbox + " cannot be parsed");
            }
            double bboxWidth = bboxLocal.getEnvelopeInternal().getWidth();
            double ratio = bboxWidth / imageWidth * 100;

            boolean zoomToLow = ratio > 50;

            String subRequest;
            if (zoomToLow) {
                subRequest = "(SELECT SUM(ST_CoveredBy(ga.location,gb.location )::integer) ";
            } else {
                //too heavy to use with little zoom
                subRequest = "(SELECT SUM(ST_CoveredBy(ga.location,ST_Translate(ST_Scale(gb.location, " + xfactor + "," + yfactor + "), ST_X(ST_Centroid(gb.location))*(1 - " + xfactor + "), ST_Y(ST_Centroid(gb.location))*(1 - " + yfactor + ") ))::integer) ";

            }

            subRequest = subRequest +
                    "FROM reviewed_annotation ga, reviewed_annotation gb " +
                    "WHERE ga.id=a.id " +
                    "AND ga.id<>gb.id " +
                    "AND ga.image_id=gb.image_id " +
                    "AND ST_Intersects(gb.location,ST_GeometryFromText(:bbox_subRequest,0)))\n";
            parameters.put("bbox_subRequest", bbox);

            //orderBy = ['numberOfCoveringAnnotation':'asc','id':'asc']
            orderBy = new LinkedHashMap<>(Map.of("id", "desc"));
            //addExtraColumn("numberOfCoveringAnnotation",subRequest)
            return subRequest;
        }
        return "";
    }

    String getNotReviewedOnlyConst() {
        return "";
    }

    String createOrderBy() {
        if (kmeansValue < 3) return "";
        if (orderBy != null && !orderBy.isEmpty()) {
            return "ORDER BY " + orderBy.entrySet().stream().map(x -> x.getKey() + " " + x.getValue()).collect(Collectors.joining(", "));
        } else {
            return "ORDER BY a.id desc " + ((term != null || terms != null) ? ", at.term_id " : "");
        }
    }
}
