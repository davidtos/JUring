package bench;

import com.davidvlijmincx.lio.api.JLibUringBlocking;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
public class ExecutionPlanVirtual {

    JLibUringBlocking q;

    @Setup
    public void setup() {
        q = new JLibUringBlocking(2500);

    }

    @TearDown
    public void tearDown() throws Throwable {
        q.close();
    }

}
