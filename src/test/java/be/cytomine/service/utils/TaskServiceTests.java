package be.cytomine.service.utils;

import be.cytomine.BasicInstanceBuilder;
import be.cytomine.CytomineCoreApplication;
import be.cytomine.domain.project.Project;
import be.cytomine.domain.security.SecRole;
import be.cytomine.service.security.SecRoleService;
import be.cytomine.utils.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import javax.transaction.Transactional;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(classes = CytomineCoreApplication.class)
@AutoConfigureMockMvc
@Transactional
@WithMockUser(authorities = "ROLE_SUPER_ADMIN", username = "superadmin")
public class TaskServiceTests {


    @Autowired
    BasicInstanceBuilder builder;

    @Autowired
    TaskService taskService;

    @Test
    public void get_task_empty() {
        assertThat(taskService.get(0L)).isNull();
    }

    @Test
    public void task_workflow() {
        Project project = builder.given_a_project();
        Task newTask = taskService.createNewTask(project, builder.given_superadmin(), true);
        assertThat(newTask).isNotNull();
        assertThat(newTask.getProgress()).isEqualTo(0);

        assertThat(taskService.get(newTask.getId())).isNotNull();

        taskService.updateTask(newTask, 50, "in the middle of the task");

        taskService.updateTask(newTask, 90, "almost done");

        assertThat(taskService.get(newTask.getId()).getProgress()).isEqualTo(90);

        assertThat(taskService.listLastComments(project)).hasSize(2);
        assertThat(taskService.getLastComments(newTask,1)).hasSize(1);
        assertThat(taskService.getLastComments(newTask,1).get(0)).isNotEqualTo("almost done");

        taskService.finishTask(newTask);

        assertThat(taskService.get(newTask.getId()).getProgress()).isEqualTo(100);
    }
}
