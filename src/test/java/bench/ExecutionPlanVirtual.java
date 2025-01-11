package bench;

import com.davidvlijmincx.lio.api.JUringBlocking;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
public class ExecutionPlanVirtual {

    JUringBlocking q;

    @Setup
    public void setup() {
        q = new JUringBlocking(2500);

    }

    @TearDown
    public void tearDown() throws Throwable {
        q.close();
    }

}
