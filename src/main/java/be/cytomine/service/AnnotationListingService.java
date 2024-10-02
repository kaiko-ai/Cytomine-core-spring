package be.cytomine.service;

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

import be.cytomine.exceptions.WrongArgumentException;
import be.cytomine.repository.AlgoAnnotationListing;
import be.cytomine.repository.AnnotationListing;
import be.cytomine.repository.UserAnnotationListing;
import be.cytomine.service.dto.AnnotationResult;
import be.cytomine.service.dto.Point;
import be.cytomine.service.security.SecurityACLService;
import be.cytomine.service.utils.KmeansGeometryService;
import be.cytomine.utils.GisUtils;
import be.cytomine.utils.JsonObject;
import liquibase.pro.packaged.f;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TupleElement;
import javax.transaction.Transactional;
import java.math.BigInteger;
import java.util.*;

import static org.springframework.security.acls.domain.BasePermission.READ;

@Transactional
@Service
public class AnnotationListingService  {

    @Autowired
    private SecurityACLService securityACLService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private KmeansGeometryService kmeansGeometryService;


    public List listGeneric(AnnotationListing al) {
        securityACLService.check(al.container(),READ);
        if((al.getKmeans()!=null && al.getKmeans()) && al.getKmeansValue()==null) {
            if(al.getBbox()==null) {
                throw new WrongArgumentException("If you want to use kmeans, you must provide image bbox:" + al.getBbox());
            }

            if (al.getSlice()==null && (al.getSlices()==null || al.getSlices().isEmpty())) {
                throw new WrongArgumentException("If you want to use kmeans, you must provide slice Id");
            }
            al.setSlices((al.getSlices()==null || al.getSlices().isEmpty()) ? List.of(al.getSlice()) : al.getSlices());
            Integer rule = kmeansGeometryService.mustBeReduce(al.getSlices(),al.getUser(),al.getBbox());
            al.setKmeansValue(rule);
        } else if(al.getKmeans()!=null && !al.getKmeans()){
            //no kmeans
            al.setKmeansValue(KmeansGeometryService.FULL);
        }
        return executeRequest(al);
    }

    public List executeRequest(AnnotationListing al) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if(al.getKmeansValue()==KmeansGeometryService.FULL) {
            return selectGenericAnnotation(al);
        } else if(al.getKmeansValue()==KmeansGeometryService.KMEANSFULL) {
            return kmeansGeometryService.doKeamsFullRequest(al.getAnnotationsRequest(parameters), parameters);
        } else {
            return kmeansGeometryService.doKeamsSoftRequest(al.getAnnotationsRequest(parameters), parameters);
        }
    }

    /**
     * Execute request and format result into a list of map
     */
    private List<AnnotationResult> selectGenericAnnotation(AnnotationListing al) {

        List<AnnotationResult> data = new ArrayList<>();
        long lastAnnotationId = -1;
        long lastTermId = -1;
        long lastTrackId = -1;

        boolean first = true;

        List<String> realColumn = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        String request = al.getAnnotationsRequest(parameters);

        boolean termAsked = false;
        boolean trackAsked = false;

        List<String> excludedColumns = List.of("annotationTerms", "annotationTracks", "userTerm", "x", "y");


        Query nativeQuery = entityManager.createNativeQuery(request, Tuple.class);
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            nativeQuery.setParameter(entry.getKey(), entry.getValue());
        }
        List<Tuple> resultList = nativeQuery.getResultList();

        for (Tuple rowResult : resultList) {
            Map<String, Object> tuple = new LinkedHashMap<>();
            for (TupleElement<?> element : rowResult.getElements()) {
                Object value = rowResult.get(element.getAlias());
                if (value instanceof BigInteger) {
                    value = ((BigInteger)value).longValue();
                }
                tuple.put(element.getAlias(), value);
            }

            /**
             * If an annotation has n multiple term, it will be on "n" lines.
             * For the first line for this annotation (it.id!=lastAnnotationId), add the annotation data,
             * For the other lines, we add term data to the last annotation
             */
            if ((Long)tuple.get("id") != lastAnnotationId) {
                termAsked = false;
                trackAsked = false;

                if(first) {
                    for (String columnName : al.getAllPropertiesName()) {
                        if(columnExists(tuple, columnName) && !excludedColumns.contains(columnName)) {
                            realColumn.add(columnName);
                        }
                    }
                    first = false;
                }

                AnnotationResult item = new AnnotationResult();
                item.put("class", al.getDomainClass());

                for (String columnName : realColumn) {
                    item.put(columnName, tuple.get(columnName)!=null? tuple.get(columnName) : tuple.get(columnName.toLowerCase()));
                }


                if(al.getColumnsToPrint().contains("term")) {
                    termAsked = true;
                    item.put("term", tuple.get("term")!=null? buildList(tuple.get("term")) : new ArrayList<>());
                    item.put("userByTerm",
                            tuple.get("term")!=null?
                                    buildList(new HashMap<>(JsonObject.of("id", tuple.get("annotationterms"), "term", tuple.get("term"), "user", buildList(tuple.get("userterm"))))) : new ArrayList<>());
                }

                if (al.getColumnsToPrint().contains("track") && (al instanceof UserAnnotationListing || al instanceof AlgoAnnotationListing)) {
                    trackAsked = true;
                    item.put("track", (tuple.get("track")!=null ? buildList(tuple.get("track")) : new ArrayList<>()));
                    item.put("annotationTrack", (tuple.get("track")!=null ? buildList(new HashMap<>(Map.of("id", tuple.get("annotationtracks"), "track", tuple.get("track")))) : new ArrayList<>()));
                }

                if(al.getColumnsToPrint().contains("gis")) {
                    item.put("perimeterUnit", tuple.get("perimeterunit") != null? GisUtils.retrieveUnit((Integer)tuple.get("perimeterunit")) : null);
                    item.put("areaUnit", tuple.get("areaunit") != null? GisUtils.retrieveUnit((Integer)tuple.get("areaunit")) : null);
                    item.put("centroid", new Point((Double)tuple.get("x"), (Double)tuple.get("y")));
                }

                if(al.getColumnsToPrint().contains("meta")) {
                    if(al.getClass().getName().contains("UserAnnotation")) {
                        item.put("cropURL",UrlApi.getUserAnnotationCropWithAnnotationId((Long)tuple.get("id"), "png"));
                        item.put("smallCropURL",UrlApi.getUserAnnotationCropWithAnnotationIdWithMaxSize((Long)tuple.get("id"), 256, "png"));
                        item.put("url",UrlApi.getUserAnnotationCropWithAnnotationId((Long)tuple.get("id"), "png"));
                        item.put("imageURL",UrlApi.getAnnotationURL((Long)tuple.get("project"), (Long)tuple.get("image"), (Long)tuple.get("id")));
                    } else if(al.getClass().getName().contains("AlgoAnnotation")) {
                        item.put("cropURL",UrlApi.getAlgoAnnotationCropWithAnnotationId((Long)tuple.get("id"), "png"));
                        item.put("smallCropURL",UrlApi.getAlgoAnnotationCropWithAnnotationIdWithMaxSize((Long)tuple.get("id"), 256, "png"));
                        item.put("url",UrlApi.getAlgoAnnotationCropWithAnnotationId((Long)tuple.get("id"), "png"));
                        item.put("imageURL",UrlApi.getAnnotationURL((Long)tuple.get("project"), (Long)tuple.get("image"), (Long)tuple.get("id")));
                    } else if(al.getClass().getName().contains("ReviewedAnnotation")) {
                        item.put("cropURL",UrlApi.getReviewedAnnotationCropWithAnnotationId((Long)tuple.get("id"), "png"));
                        item.put("smallCropURL",UrlApi.getReviewedAnnotationCropWithAnnotationIdWithMaxSize((Long)tuple.get("id"), 256, "png"));
                        item.put("url",UrlApi.getReviewedAnnotationCropWithAnnotationId((Long)tuple.get("id"), "png"));
                        item.put("imageURL",UrlApi.getAnnotationURL((Long)tuple.get("project"), (Long)tuple.get("image"), (Long)tuple.get("id")));
                    }
                }
                data.add(item);
            } else {
                AnnotationResult lastResult = data.get(data.size()-1);
                if (termAsked && tuple.get("term")!=null) {
                    List lasResultUserByTerm = (List) lastResult.get("userByTerm");
                    Map userByTerm = (Map)(lasResultUserByTerm).get(lasResultUserByTerm.size()-1);
                    List term = ((List)lastResult.get("term"));
                    if ((Long)tuple.get("term") == lastTermId) {
                        if (!((List)(userByTerm.get("user"))).contains(tuple.get("userterm"))) {
                            ((List)(userByTerm.get("user"))).add(tuple.get("userterm"));
                        }
                    } else if (!term.contains(tuple.get("term"))) {
                        ((List)(lastResult.get("term"))).add(tuple.get("term"));
                        ((List)lastResult.get("userByTerm")).add(new HashMap<>(Map.of("id", tuple.get("annotationterms"), "term", tuple.get("term"), "user", buildList(tuple.get("userterm")))));
                    }
                }

                if (trackAsked && tuple.get("track")!=null && (long)tuple.get("track") != lastTrackId && !((List)lastResult.get("track")).contains(tuple.get("track"))) {
                    ((List)lastResult.get("track")).add(tuple.get("track"));
                    ((List)lastResult.get("annotationTrack")).add(new HashMap<>(Map.of("id", tuple.get("annotationtracks"), "track", tuple.get("track") )));
                }
            }

            if (termAsked) {
                lastTermId = (tuple.get("term")!=null? (long)tuple.get("term") : -1);
            }

            if (trackAsked) {
                lastTrackId = (tuple.get("track")!=null? (long)tuple.get("track") : -1);
            }

            lastAnnotationId = (long)tuple.get("id");
        }
        return data;
    }

    private boolean columnExists(Map<String, Object> tuple, String columnName) {
        return tuple.get(columnName) != null || tuple.get(columnName.toLowerCase()) != null;
    }

    List buildList(Object firstElement) {
        if (firstElement==null) {
            return new ArrayList();
        }
        List list = new ArrayList();
        list.add(firstElement);
        return list;
    }
//
//    boolean columnExist(Tuple rs, String column) {
//        try {
//            rs.get(column.toLowerCase());
//            return true;
//        } catch (IllegalArgumentException exception) {
//            return false;
//        }
//    }


}
