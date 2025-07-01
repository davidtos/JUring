package bench;

import com.davidvlijmincx.lio.api.JUring;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

@State(Scope.Thread)
public class ExecutionPlanJUring {

    public JUring jUring;

    @Setup
    public void setup() {
        jUring = new JUring(2500, IORING_SETUP_SINGLE_ISSUER);

    }

    @TearDown
    public void tearDown() throws Throwable {
        jUring.close();
    }

}
