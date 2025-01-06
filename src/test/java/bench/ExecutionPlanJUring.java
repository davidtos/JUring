package bench;

import com.davidvlijmincx.lio.api.JUring;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
public class ExecutionPlanJUring {

    JUring q;

    @Setup
    public void setup() {
        q = new JUring(2500, false);

    }

    @TearDown
    public void tearDown() throws Throwable {
        q.close();
    }

}
