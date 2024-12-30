package bench;

import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.virtual.JLibUringVirtual;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
public class ExecutionPlanVirtual {

    JLibUringVirtual q;

    @Setup
    public void setup() {
        q = new JLibUringVirtual(2500, false);

    }

    @TearDown
    public void tearDown() throws Throwable {
        q.close();
    }

}
