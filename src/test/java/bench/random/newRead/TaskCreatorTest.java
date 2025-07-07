package bench.random.newRead;


public class TaskCreatorTest {

    public static void main(String[] args) {
        TaskCreator taskCreator = new TaskCreator();

        Task[] tasks = taskCreator.getTasks(10, 1);

        for (Task task : tasks) {
            System.out.println(task);
        }

        System.out.println("tasks.length = " + tasks.length);

    }
}
