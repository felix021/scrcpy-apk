package cn.felix021.scrcpy;
import org.junit.Test;
import static org.junit.Assert.*;
public class KeyboardStateTest {
    @Test public void windowsModifierSupportsOneShotLockAndReset() {
        KeyboardState s=new KeyboardState();s.win=1;s.ctrl=1;
        assertEquals(0x33000,s.meta());s.consume();assertEquals(0,s.meta());
        s.win=2;s.shift=1;assertEquals(0x30041,s.meta());s.consume();
        assertEquals(0x30000,s.meta());s.clear();assertEquals(0,s.meta());
    }
    @Test public void qwertySwipeAlternatesMatchKeyHints() {
        String keys="qwertyuiopasdfghjklzxcvbnm.";
        String expected="1234567890-/:;()~'\"@_#&?!.,";
        for(int i=0;i<keys.length();i++){
            char value=KeyboardState.alternate(keys.charAt(i));
            assertEquals(expected.charAt(i),value);
            assertEquals(value,KeyboardState.alternate(Character.toUpperCase(keys.charAt(i))));
            assertTrue(KeyboardState.code(value)>0);
        }
        assertEquals(0,KeyboardState.alternate('['));
    }
    @Test public void oneShotClearsButLockedModifiersPersist() {
        KeyboardState s=new KeyboardState();s.ctrl=1;s.alt=2;s.shift=1;s.fn=1;s.caps=true;
        assertEquals(0x103053,s.meta());s.consume();
        assertEquals(0,s.ctrl);assertEquals(2,s.alt);assertEquals(0,s.shift);assertEquals(0,s.fn);
        assertTrue(s.caps);assertEquals(0x100012,s.meta());s.clear();assertEquals(0,s.meta());
    }
    @Test public void capsAndShiftHaveStandardLetterCaseSemantics() {
        KeyboardState s=new KeyboardState();assertFalse(s.upper());s.caps=true;assertTrue(s.upper());
        s.shift=1;assertFalse(s.upper());assertEquals(0x100041,s.meta());
        assertFalse(KeyboardState.needsShift('a'));assertTrue(KeyboardState.needsShift('?'));
    }
    @Test public void shiftLabelsMatchPhysicalPunctuation() {
        assertEquals('<',KeyboardState.shifted(','));assertEquals('{',KeyboardState.shifted('['));
        assertEquals('!',KeyboardState.shifted('1'));assertEquals(')',KeyboardState.shifted('0'));
        assertEquals('A',KeyboardState.shifted('a'));
    }
    @Test public void printableUSKeyboardCharactersAllHavePhysicalKeys() {
        for(char c='!';c<='~';c++)assertTrue("missing "+c,KeyboardState.code(Character.toLowerCase(c))>0);
        assertEquals(29,KeyboardState.code('a'));assertEquals(8,KeyboardState.code('!'));
        assertEquals(73,KeyboardState.code('|'));assertEquals(76,KeyboardState.code('?'));
    }
}
