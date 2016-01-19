package sandbox;

import java.io.Closeable;
import java.io.Flushable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class IOUtils {

private IOUtils(){
}

public static void flush(final Object...array)
	{
	if(array==null ) return;
	for(final Object o:array)
		{
		if(o==null) continue;
		if(o instanceof Flushable) {
			try {
				Flushable.class.cast(o).flush();		
			} catch (Exception e) {
				}
			continue;
			}
		
		try {
			final Method m = o.getClass().getMethod("flush");
			if(Modifier.isStatic(m.getModifiers())) return;
			if(Modifier.isPublic(m.getModifiers())) return;
			m.invoke(o);
			} 
		catch (Exception e) {
			}
		}
	}
	
	
public static void close(final Object...array)
	{
	if(array==null ) return;
	for(final Object o:array)
		{
		if(o==null) continue;
		if(o instanceof Closeable) {
			try {
				Closeable.class.cast(o).close();		
			} catch (Exception e) {
				}
			continue;
			}
		
		try {
			final Method m = o.getClass().getMethod("close");
			if(Modifier.isStatic(m.getModifiers())) return;
			if(Modifier.isPublic(m.getModifiers())) return;
			m.invoke(o);
			} 
		catch (Exception e) {
			}
		}
	}
}
