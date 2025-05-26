package bench;

import com.davidvlijmincx.lio.api.JUring;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.lang.foreign.Arena;

@State(Scope.Thread)
public class ExecutionPlanJUring {

    public JUring jUring;
    public Arena arena = Arena.ofConfined();

    @Setup
    public void setup() {
        jUring = new JUring(2301, 4096, 2301);

    }

    @TearDown
    public void tearDown() throws Throwable {
        jUring.close();
        arena.close();
    }

}
