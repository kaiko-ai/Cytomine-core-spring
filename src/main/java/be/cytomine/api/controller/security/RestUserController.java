package be.cytomine.api.controller.security;

import be.cytomine.api.controller.RestCytomineController;
import be.cytomine.domain.project.Project;
import be.cytomine.domain.security.SecUser;
import be.cytomine.domain.security.User;
import be.cytomine.exceptions.ObjectNotFoundException;
import be.cytomine.service.CurrentUserService;
import be.cytomine.service.project.ProjectService;
import be.cytomine.service.search.ProjectSearchExtension;
import be.cytomine.service.search.UserSearchExtension;
import be.cytomine.service.security.SecUserService;
import be.cytomine.utils.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.springframework.security.acls.domain.BasePermission.READ;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class RestUserController extends RestCytomineController {

    private final SecUserService secUserService;

    private final ProjectService projectService;

    private final CurrentUserService currentUserService;

    @GetMapping("/user/{id}")
    public ResponseEntity<String> getUser(
            @PathVariable String id
    ) {
        log.debug("REST request to get User : {}", id);

        Optional<SecUser> user = secUserService.find(id);
        if (user.isEmpty()) {
            user = secUserService.findByUsername(id);
        }

        return user.map( secUser -> {
            JsonObject object = User.getDataFromDomain(secUser);
            //TODO
//            JsonObject  authMaps = secUserService.getAuth(user);
//            object.put("admin", authMaps.get("admin"));
//            object.put("user", authMaps.get("user"));
//            object.put("guest", authMaps.get("guest"));
            return ResponseEntity.ok(convertObjectToJSON(object));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(responseNotFound("User", id).toJsonString()));
    }

    @GetMapping("/user/current.{ext}")
    public ResponseEntity<String> getCurrentUser(
    ) {
        log.debug("REST request to get current User");

        Optional<SecUser> user = secUserService.findCurrentUser();
        //TODO: admin, user, guest, adminByNow...
        return user.map( secUser -> {
            JsonObject object = User.getDataFromDomain(secUser);
            return ResponseEntity.ok(convertObjectToJSON(object));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(responseNotFound("User", "current").toJsonString()));
    }


    /**
     * Add a new user
     */
    @PostMapping("/user")
    public ResponseEntity<String> createUser(@RequestBody JsonObject json) {
        log.debug("REST request to save User : " + json);
        return add(secUserService, json);
    }

    @PutMapping("/user/{id}")
    public ResponseEntity<String> updateUser(@PathVariable String id, @RequestBody JsonObject json) {
        log.debug("REST request to update User : {}", id);
        return update(secUserService, json);
    }

    /**
     * Delete the "login" User.
     */
    @DeleteMapping("/user/{id}")
    public ResponseEntity<String> deleteUser(@PathVariable String id) {
        log.debug("REST request to delete User: {}", id);
        return delete(secUserService, JsonObject.of("id", Long.parseLong(id)), null);
    }

    /**
     * List all ontology visible for the current user
     * For each ontology, print the terms tree
     */
    @GetMapping("/project/{id}/user.json")
    public ResponseEntity<JsonObject> list(
            @PathVariable Long id,
            @RequestParam(value = "showJob", defaultValue = "false", required = false) Boolean showJob,
            @RequestParam(value = "withLastImage", defaultValue = "false", required = false) Boolean withLastImage,
            @RequestParam(value = "withLastConsultation", defaultValue = "false", required = false) Boolean withLastConsultation,
            @RequestParam(value = "withNumberConsultations", defaultValue = "false", required = false) Boolean withNumberConsultations,
            @RequestParam(value = "sortColumn", defaultValue = "created", required = false) String sortColumn,
            @RequestParam(value = "sortDirection", defaultValue = "desc", required = false) String sortDirection,
            @RequestParam(value = "offset", defaultValue = "0", required = false) Long offset,
            @RequestParam(value = "max", defaultValue = "0", required = false) Long max,
            @RequestParam Map<String,String> allParams
    ) {
        log.debug("REST request to list user from project {}", id);
        Project project = projectService.find(id)
                .orElseThrow(() -> new ObjectNotFoundException("Project", id));

        UserSearchExtension userSearchExtension = new UserSearchExtension();
        userSearchExtension.setWithLastImage(withLastImage);
        userSearchExtension.setWithLastConnection(withLastConsultation);
        userSearchExtension.setWithNumberConnections(withNumberConsultations);
        userSearchExtension.setWithUserJob(showJob);

        // TODO: retrieve search parameter
        return responseSuccess(
                secUserService.listUsersExtendedByProject(project, userSearchExtension, new ArrayList<>(), sortColumn, sortDirection, max, offset), allParams
        );

        //return responseSuccess(projectService.list(user, projectSearchExtension, new ArrayList<>(), "created", "desc", 0L, 0L), allParams);
    }

//    @RestApiMethod(description="Get all project users. Online flag may be set to get only online users", listing = true)
//    @RestApiParams(params=[
//    def showByProject() {
//
//        def extended = [:]

//        String sortColumn = params.sort ?: "created"
//        String sortDirection = params.order ?: "desc"
//
//        def results = secUserService.listUsersExtendedByProject(project, extended, searchParameters, sortColumn, sortDirection, params.long('max',0), params.long('offset',0))
//
//        responseSuccess([collection : results.data, size:results.total, offset: results.offset, perPage: results.perPage, totalPages: results.totalPages])
//
//        //boolean showUserJob = params.boolean('showJob')
//    }


//    @GetMapping("/project/{id}/user.json")
//    public ResponseEntity<String> showByProject(
//            @PathVariable String id
//    ) {
//        log.debug("REST request to get project users : {}", id);
//        secUserService.li
//    }


}
