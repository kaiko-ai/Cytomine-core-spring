package be.cytomine.domain.project;

import be.cytomine.domain.CytomineDomain;
import be.cytomine.domain.command.CommandHistory;
import be.cytomine.domain.image.server.Storage;
import be.cytomine.domain.ontology.Ontology;
import be.cytomine.domain.ontology.UserAnnotation;
import be.cytomine.domain.security.User;
import be.cytomine.utils.JsonObject;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;

import javax.persistence.*;
import java.util.Set;

@Entity
@Getter
@Setter
public class Project extends CytomineDomain {

    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ontology_id", nullable = true)
    private Ontology ontology;

    @OneToMany(mappedBy="project")
    @LazyCollection(LazyCollectionOption.EXTRA)
//    @JoinColumn(referencedColumnName = "ontology_id", nullable = true)
    private Set<UserAnnotation> userAnnotations;

    @OneToMany(mappedBy="project", fetch = FetchType.LAZY)
    private Set<CommandHistory> commandHistories;

    @OneToMany(mappedBy = "project")
    private Set<ProjectRepresentativeUser> representativeUsers;

    @OneToMany
    @JoinColumn(name="id", referencedColumnName = "id")
    private Set<AdminProjectView> adminProjectViews;

    private Boolean blindMode = false;

    private Boolean areImagesDownloadable = false;

    private long countAnnotations;

    private long countJobAnnotations;

    private long countImages;

    private long countReviewedAnnotations;

    private boolean retrievalDisable = false;

    private boolean retrievalAllOntology = true;

    private boolean isClosed = false;

    private boolean hideUsersLayers = false;

    private boolean hideAdminsLayers = false;

    @Enumerated(EnumType.STRING)
    EditingMode mode = EditingMode.CLASSIC;

    public CytomineDomain buildDomainFromJson(JsonObject json, EntityManager entityManager) {
        Project project = (Project)this;
        project.id = json.getJSONAttrLong("id",null);
        project.name = json.getJSONAttrStr("name");
        project.ontology = (Ontology) json.getJSONAttrDomain(entityManager, "ontology", new Ontology(), true);
        project.blindMode = json.getJSONAttrBoolean("blindMode", false);
        project.areImagesDownloadable = json.getJSONAttrBoolean("areImagesDownloadable", false);
        project.isClosed = json.getJSONAttrBoolean("isClosed", false);
        project.mode = EditingMode.CLASSIC;
        if(json.getJSONAttrBoolean("isRestricted", false)) {
            project.mode = EditingMode.RESTRICTED;
        }
        if(json.getJSONAttrBoolean("isReadOnly", false)) {
            project.mode = EditingMode.READ_ONLY;
        }

        project.hideUsersLayers = json.getJSONAttrBoolean("hideUsersLayers", false);
        project.hideAdminsLayers = json.getJSONAttrBoolean("hideAdminsLayers", false);


        project.created = json.getJSONAttrDate("created");
        project.updated = json.getJSONAttrDate("updated");
        return project;
    }

    public static JsonObject getDataFromDomain(CytomineDomain domain) {
        JsonObject returnArray = CytomineDomain.getDataFromDomain(domain);
        Project project = (Project)domain;
        returnArray.put("name", project.getName());
        returnArray.put("ontology", (project.getOntology()!=null ? project.getOntology().getId() : null));
        returnArray.put("ontologyName", (project.getOntology()!=null ? project.getOntology().getName() : null));

        returnArray.put("blindMode", project.getBlindMode()!=null && project.getBlindMode());
        returnArray.put("areImagesDownloadable", project.getAreImagesDownloadable()!=null && project.getAreImagesDownloadable());

        returnArray.put("numberOfSlides", 0);
        returnArray.put("numberOfImages", project.countImages);
        returnArray.put("numberOfAnnotations", project.countAnnotations);
        returnArray.put("numberOfJobAnnotations", project.countJobAnnotations);
        returnArray.put("numberOfReviewedAnnotations", project.countReviewedAnnotations);
        returnArray.put("isClosed", project.isClosed());
        returnArray.put("isReadOnly", false);
        if (project.getMode()!=null && project.getMode().equals(EditingMode.READ_ONLY)) {
            returnArray.put("isReadOnly", true);
        }
        returnArray.put("isRestricted", false);
        if (project.getMode()!=null && project.getMode().equals(EditingMode.RESTRICTED)) {
            returnArray.put("isRestricted", true);
        }

        returnArray.put("hideUsersLayers", true);
        returnArray.put("hideAdminsLayers", true);

        return returnArray;
    }

    @Override
    public String toJSON() {
        return getDataFromDomain(this).toJsonString();
    }

    @Override
    public JsonObject toJsonObject() {
        return getDataFromDomain(this);
    }

    public boolean canUpdateContent() {
        return !mode.equals(EditingMode.READ_ONLY);
    }

    public CytomineDomain container() {
        return this;
    }
}
