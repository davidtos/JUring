package bench;

import com.davidvlijmincx.lio.api.JUringBlocking;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.time.Duration;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

@State(Scope.Thread)
public class ExecutionPlanBlocking {

    public JUringBlocking jUringBlocking;

    @Setup
    public void setup() {
        jUringBlocking = new JUringBlocking(2500, Duration.ofMillis(1), IORING_SETUP_SINGLE_ISSUER);

    }

    @TearDown
    public void tearDown() throws Throwable {
        jUringBlocking.close();
    }

}
