package studio.usergist.feedback;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResetCompatibilityTest {
    @Test public void retainsZeroArgumentJavaReset() throws Exception {
        Runnable reset = UserGist::reset;
        assertNotNull(reset);
        assertEquals(void.class, UserGist.class.getMethod("reset").getReturnType());
    }
}
