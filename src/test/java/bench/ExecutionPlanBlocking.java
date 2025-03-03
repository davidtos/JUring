package bench;

import com.davidvlijmincx.lio.api.JUringBlocking;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
public class ExecutionPlanBlocking {

    JUringBlocking jUringBlocking;

    @Setup
    public void setup() {
        jUringBlocking = new JUringBlocking(2500,1);

    }

    @TearDown
    public void tearDown() throws Throwable {
        jUringBlocking.close();
    }

}
