package sandbox;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;


import com.beust.jcommander.Parameter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
/**
```
$ crontab -e

0 0-23 * * * /home/lindenb/bin/insta2atom.sh

$ cat /home/lindenb/bin/insta2atom.sh

```bash
#!/bin/bash

java -jar ${HOME}/src/jsandbox/dist/insta2atom.jar |\
	xmllint --format --output  "${HOME}/public_html/feed/instagram.xml" -
```


 */
public class InstagramToAtom extends Launcher {
	private static final Logger LOG = Logger.builder(InstagramToAtom.class).build();
	private int ID_GENERATOR=0;
	@Parameter(names={"-t","--tumb-size"},description="Thumb size.")
	private int thumb_size =256;
	@Parameter(names={"-f","--force"},description="Force print only new items, discard the non-updated.")
	private boolean force_print_new_only = false;
	@Parameter(names={"-s","--seconds"},description="Sleep s seconds between each calls.")
	private int sleep_seconds = 5;
	@Parameter(names={"-d","--directory"},description="Cache directory. default: ${HOME}/.insta2atom ")
	private File cacheDirectory  = null;
	@Parameter(names={"-c","--cookies"},description=CookieStoreUtils.OPT_DESC)
	private File cookieStoreFile  = null;
	@Parameter(names={"-g","--group"},description="group items per query")
	private boolean group_flag =false;
	@Parameter(names={"-i","--cache"},description="Cache file. null: default")
	private File customCacheFile = null;

	
	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private CloseableHttpClient client = null;
	

	private static class Thumbnail extends Dimension
		{
		String src;
		}
	
	private static class Dimension
		{
		int width=0;
		int height=0;
		}
	
	
	
	private class QueryIterator extends AbstractIterator<Query>
		{
		FileInputStream fin;
		XMLEventReader reader = null;
		QueryIterator(final File xmlFile) throws IOException,XMLStreamException{
			this.fin = new FileInputStream(xmlFile);
			this.reader = XMLInputFactory.newFactory().createXMLEventReader(this.fin);
			}
		
		@Override
		protected Query advance() {
			try {
				if(fin==null || reader==null) return null;
				for(;;) {
					if(!reader.hasNext()) {
						close();
						return null;
						}
					final XMLEvent evt=reader.nextEvent();
					if(!evt.isStartElement()) continue;
					final StartElement E=evt.asStartElement();
					if(!E.getName().getLocalPart().equals("query")) {
						continue;
						}
					return new Query(reader,E);
					}
			} catch(IOException|XMLStreamException err)
				{
				throw new RuntimeException(err);
				}
			}
		@Override
		public void close() throws IOException {
			IOUtils.close(this.reader);
			IOUtils.close(this.fin);
			this.reader=null;
			this.fin=null;
			}
		}
	
	private static String getAttribute(final StartElement root,final String name) {
		final Attribute att = root.getAttributeByName(new QName(name));
		return att==null?"":att.getValue();
		}
	
	private static boolean hasAttribute(final StartElement root,final String name) {
		return root.getAttributeByName(new QName(name))!=null;
		}

	
	private class Query
		{
		final String query;
		Date date = new Date();
		String md5 = "";
		Integer max_likes=null;
		long max_diff_sec = -1L;
		int cache_max_size = 100;
		final Set<INode> old_images_urls;
		final Set<INode> new_images_urls = new TreeSet<>();
		Query(final XMLEventReader r,final StartElement root) throws XMLStreamException
			{
			this.query= getAttribute(root,"name");
			LOG.debug("found query "+this.query);
			if(hasAttribute(root,"date"))
				{
				try {
					this.date = dateFormatter.parse(getAttribute(root,"date"));
					}
				catch(final ParseException err) {
					this.date = new Date();
					}
				}
			
			if(hasAttribute(root,"diff")) {
				int factor =1;
				String diff = getAttribute(root,"diff");
				if(diff.endsWith("s")) {
					factor=1;
					diff=diff.substring(0,diff.length()-1);
					}
				else if(diff.endsWith("m")) {
					factor=60;
					diff=diff.substring(0,diff.length()-1);
					}
				else if(diff.endsWith("h")) {
					factor=60*60;
					diff=diff.substring(0,diff.length()-1);
					}
				try {
					this.max_diff_sec = Long.parseLong(diff) * factor;
					}
				catch(final NumberFormatException err) {
				this.max_diff_sec = -1L;
				}
			}
			

			if(hasAttribute(root,"max-size")) {
				try {
					this.cache_max_size = Integer.parseInt(getAttribute(root,"max-size"));
					}
				catch(final NumberFormatException err) {
				this.cache_max_size = 100;
				}
			}
			
			if(hasAttribute(root,"max_likes")) {
				try {
					this.max_likes = new Integer(getAttribute(root,"max_likes"));
					}
				catch(final Exception err) {
					this.max_likes = null;
					}
				}
			if(hasAttribute(root,"md5")) {
				this.md5 = getAttribute(root,"md5");
				}
			
			while(r.hasNext())
				{
				final XMLEvent evt =r.nextEvent();
				if(evt.isEndElement()) break;
				if(!evt.isStartElement()) continue;
				final StartElement E =evt.asStartElement();
				if(!E.getName().getLocalPart().equals("image")) continue;
				final INode img = new INode(r,E);
				
				this.new_images_urls.add(img);
				}
			
			this.old_images_urls = Collections.unmodifiableSet(new HashSet<>(this.new_images_urls));
			}	
		
		
		void saveCache(final XMLStreamWriter w) throws XMLStreamException {
			w.writeStartElement("query");
			w.writeAttribute("name", this.query);
			w.writeAttribute("md5", this.md5);
			if(max_likes!=null) w.writeAttribute("max_likes",String.valueOf(this.max_likes));
			if(max_diff_sec>0) w.writeAttribute("diff", String.valueOf(this.max_diff_sec));
			if(cache_max_size>0) w.writeAttribute("max-size",String.valueOf(this.cache_max_size));
			w.writeAttribute("date",dateFormatter.format(this.date));
			
			final List<INode> array=new ArrayList<>(this.new_images_urls);
			array.sort((I1,I2)->I1.compareTime(I2));
			while(!array.isEmpty() && array.size()>this.cache_max_size)
				{
				array.remove(0);
				}
			
			for(final INode img:array)
				{
				img.saveCache(w);
				}
			w.writeEndElement();
			}
		
		class INode implements Comparable<INode>
			{
			Dimension dimension=null;
			String display_url;
			String owner_id;
			String shortcode="";
			String taken_at_timestamp;
			String id;
			Integer edge_liked_by;
			final List<Thumbnail> thumbnails = new ArrayList<>();
			String thumbnail_src="";
			
			INode() {
				
				}
			INode(final XMLEventReader r,final StartElement root)
				{
				if(hasAttribute(root,"display_url"))
					{
					this.display_url = getAttribute(root,"display_url");
					}
				if(hasAttribute(root,"owner_id"))
					{
					this.owner_id = getAttribute(root,"owner_id");
					}
				if(hasAttribute(root,"id"))
					{
					this.id = getAttribute(root,"id");
					}
				if(hasAttribute(root,"taken_at_timestamp"))
					{
					this.taken_at_timestamp = getAttribute(root,"taken_at_timestamp");
					}
				if(hasAttribute(root,"shortcode"))
					{
					this.shortcode = getAttribute(root,"shortcode");
					}
				if(hasAttribute(root,"thumbnail_src"))
					{
					this.thumbnail_src = getAttribute(root,"thumbnail_src");
					}
				if(hasAttribute(root,"edge_liked_by"))
					{
					this.edge_liked_by = Integer.parseInt(getAttribute(root,"edge_liked_by"));
					}
				}
			
			void saveCache(final XMLStreamWriter w) throws XMLStreamException {
				w.writeStartElement("image");
				if(edge_liked_by!=null) w.writeAttribute("edge_liked_by",""+edge_liked_by);
				if(id!=null) w.writeAttribute("id",id);
				if(shortcode!=null) w.writeAttribute("shortcode",shortcode);
				w.writeAttribute("thumbnail_src",thumbnail_src);
				w.writeAttribute("display_url",display_url);
				if(owner_id!=null) w.writeAttribute("owner_id",owner_id);
				if(taken_at_timestamp!=null) w.writeAttribute("taken_at_timestamp",taken_at_timestamp);
				w.writeEndElement();
				}
			
			public String getSrc() {
				return this.thumbnail_src;
			}
			
			long getTimestampSec()
				{
				try {
					return Long.parseLong(this.taken_at_timestamp);
					}
				catch(final NumberFormatException err)
					{
					return 0L;
					}
				}
			
			public int compareTime(final INode o) {
				final int i = Long.compare(this.getTimestampSec(), o.getTimestampSec());
				return i!=0?i:compareTo(o);
			}
			
			
			@Override
			public int compareTo(final INode o)
				{
				return this.getSrc().compareTo(o.getSrc());
				}
			@Override
			public int hashCode()
				{
				return this.getSrc().hashCode();
				}
			
			public String getPageHref() {
				return "https://www.instagram.com/p/"+this.shortcode+"/";
				}
			void write(final XMLStreamWriter w) throws XMLStreamException
				{
				w.writeEmptyElement("img");
				w.writeAttribute("id", this.shortcode.isEmpty()?String.valueOf(++ID_GENERATOR):this.shortcode);
				w.writeAttribute("alt", this.getSrc());
				w.writeAttribute("src", this.getSrc());
				w.writeAttribute("width",String.valueOf(InstagramToAtom.this.thumb_size));
				w.writeAttribute("height",String.valueOf(InstagramToAtom.this.thumb_size));
				}
			@Override
			public boolean equals(final Object obj)
				{
				if(obj==this) return true;
				if(obj==null || !(this instanceof INode)) return false;
				final INode other=INode.class.cast(obj);
				if (compareTo(other)==0)return true;
				if(this.id!=null && other.id!=null && this.id.equals(other.id)) return true;
				if(this.shortcode!=null && other.shortcode!=null && this.shortcode.equals(other.shortcode)) return true;
				return false;
				}
			@Override
			public String toString()
				{
				return this.getSrc();
				}
			}
		
		
		
		String getUrl() {
			return "https://www.instagram.com"+
					(this.query.startsWith("/")?"":"/") + 
					this.query +
					(this.query.endsWith("/")?"":"/");
			}
		
		private String queryHtml() throws IOException {
			CloseableHttpResponse response = null;
			InputStream httpIn = null;
			try
				{
				response = InstagramToAtom.this.client.execute(new HttpGet(this.getUrl()));
				httpIn = response.getEntity().getContent();
				final String html = IOUtils.readStreamContent(httpIn);
				return html;
				}
			catch(final IOException err)
				{
				LOG.error(err);
				throw err;
				}
			finally
				{
				IOUtils.close(httpIn);
				IOUtils.close(response);
				}
			}
		
		private Integer parseCount(final JsonElement je)
			{
			if(je==null ||!je.isJsonObject()) return null;
			final JsonObject jObject = je.getAsJsonObject();
			if(!jObject.has("count")) return null;
			return jObject.get("count").getAsInt();
			}
		private String parseOwner(final JsonElement je)
			{
			if(je==null ||!je.isJsonObject()) return null;
			final JsonObject jObject = je.getAsJsonObject();
			if(!jObject.has("id")) return null;
			return jObject.get("id").getAsString();
			}
		
		private Dimension parseDimension(final JsonObject jObject)
			{
			Dimension n = new Dimension();
			n.width = jObject.get("width").getAsInt();
			n.height = jObject.get("height").getAsInt();
			return n;
			}
		
		private Thumbnail parseThumbail(final JsonObject jObject) {
			final Thumbnail th = new Thumbnail();
			th.width = jObject.get("config_width").getAsInt();
			th.height = jObject.get("config_height").getAsInt();
			th.src = jObject.get("src").getAsString();
			return th;
			}
		
		private INode parseNode(final JsonObject jObject)
			{
			INode n = new INode();
			n.dimension = parseDimension(jObject.get("dimensions").getAsJsonObject());
			n.display_url = jObject.get("display_url").getAsString();
			n.edge_liked_by = parseCount(jObject.get("edge_liked_by"));
			n.shortcode = jObject.get("shortcode").getAsString();
			n.taken_at_timestamp = jObject.get("taken_at_timestamp").getAsString();
			n.thumbnail_src = jObject.get("thumbnail_src").getAsString();
			n.id = jObject.get("id").getAsString();
			n.owner_id = parseOwner(jObject.get("owner"));
			if(jObject.has("thumbnail_resources")) {
				for(final JsonElement c: jObject.getAsJsonArray("thumbnail_resources"))
					{
					n.thumbnails.add(parseThumbail(c.getAsJsonObject()));
					}
				}
			
			return n;
			}
		
		private void searchNodes(final List<INode> nodes,final String key,final JsonElement elt)
			{
			if(elt.isJsonArray())
				{
				final JsonArray jArray = elt.getAsJsonArray();
				for(final JsonElement item:jArray) {
					searchNodes(nodes,key,item);
					}
				}
			else if(elt.isJsonObject())
				{
				final JsonObject jObject = elt.getAsJsonObject();
				if("node".equals(key) && jObject.has("display_url"))
					{
					final INode n = parseNode(jObject);
					if(n!=null) nodes.add(n);
					}
				else
					{
					for(final Map.Entry<String, JsonElement> kv: jObject.entrySet())
						{
						if(kv.getKey().equals("edge_hashtag_to_top_posts")) continue;
						searchNodes(nodes,kv.getKey(),kv.getValue());
						}
					}
				}
			}
		private boolean query() throws IOException
			{
			final String windowSharedData = "window._sharedData";
			final String endObject="};";
			String html = queryHtml();
			int i= html.indexOf(windowSharedData);
			if(i==-1) {
				LOG.error("cannot find "+windowSharedData+" in "+getUrl());
				return false;
				}
			html=html.substring(i+windowSharedData.length());
			i= html.indexOf("{");
			if(i==-1)
				{
				LOG.error("cannot find '{' after "+windowSharedData+" in "+getUrl());
				return false;
				}
			html=html.substring(i);
			i = html.indexOf(endObject);
			if(i==-1)
				{
				LOG.error("cannot find  "+endObject+" in "+getUrl());
				return false;
				}
			html=html.substring(0, i+1);
			final JsonReader jsr = new JsonReader(new StringReader(html));
			jsr.setLenient(true);
			final JsonParser parser = new JsonParser();
			JsonElement root;
			try {
				root = parser.parse(jsr);
				}
			catch(final JsonSyntaxException err) {
				LOG.error(err);
				return false;
				}
			finally
				{
				jsr.close();
				}
			if(!root.isJsonObject())
				{
				LOG.error("root is not json object in "+getUrl());
				return false;
				}
			final List<INode> nodes =  new ArrayList<>();
			searchNodes(nodes,null,root.getAsJsonObject());
			if(nodes.isEmpty()) 
				{
				LOG.warning("No image found for "+this.query+".");
				}
			if(this.max_likes!=null) {
				nodes.removeIf(N->N.edge_liked_by!=null && N.edge_liked_by>this.max_likes);
				}
			nodes.removeIf(N->N.getSrc()==null && N.getSrc().isEmpty());
			if(this.max_diff_sec>0L){
				final long now=System.currentTimeMillis();
				nodes.removeIf(N->(now - N.getTimestampSec()*1000L)>this.max_diff_sec*1000L);
				}
			this.new_images_urls.addAll(nodes);
			
			
			
			final String new_md5 = md5(this.new_images_urls.stream().
					map(I->I.getSrc()).
					collect(Collectors.joining(" ")));
			if(!new_md5.equals(this.md5)) {
				this.md5 = new_md5;
				this.date = new Date();
				}
			return true;
			}
		
		void writeAtom(final XMLStreamWriter w) throws XMLStreamException {
			final Set<INode> images_to_print = new TreeSet<>();
			images_to_print.addAll(this.new_images_urls);
			
			if(InstagramToAtom.this.force_print_new_only)
				{
				images_to_print.removeAll(this.old_images_urls);
				}
			if(images_to_print.isEmpty()) return;
			if(InstagramToAtom.this.group_flag)
				{
				writeGroupedImages(w,images_to_print);
				}
			else
				{
				writeSoloImages(w,images_to_print);
				}
			}
		
		void writeGroupedImages(final XMLStreamWriter w,Set<INode> images_to_print) throws XMLStreamException {
			w.writeStartElement("entry");
			
			w.writeStartElement("title");
				w.writeCharacters(this.query);
			w.writeEndElement();

			w.writeStartElement("id");
			w.writeCharacters(this.md5);
			w.writeEndElement();

			
			w.writeEmptyElement("link");
			w.writeAttribute("href", this.getUrl()+"?m="+this.md5);
			
			w.writeStartElement("updated");
				w.writeCharacters(InstagramToAtom.this.dateFormatter.format(this.date));
			w.writeEndElement();
			
			w.writeStartElement("author");
				w.writeStartElement("name");
					w.writeCharacters(this.query);
				w.writeEndElement();
			w.writeEndElement();
			
			w.writeStartElement("content"); 
			
			w.writeAttribute("type","xhtml");
			w.writeStartElement("div");
			w.writeDefaultNamespace("http://www.w3.org/1999/xhtml");
			w.writeStartElement("p");  
			
	
			for(final INode image_url:images_to_print.stream().sorted((I1,I2)->I1.compareTime(I2)).collect(Collectors.toList())) {
				w.writeStartElement("a");
				w.writeAttribute("id",String.valueOf(++ID_GENERATOR));
				w.writeAttribute("target", "_blank");
				w.writeAttribute("href", image_url.shortcode.isEmpty()?this.getUrl():image_url.getPageHref());
				image_url.write(w);
				w.writeEndElement();//a
				}
			w.writeEndElement();//p
			w.writeEndElement();//div

			w.writeEndElement();//content
			
			w.writeEndElement();//entry
		}
		
		void writeSoloImages(final XMLStreamWriter w,Set<INode> images_to_print) throws XMLStreamException {
			for(final INode image_url:images_to_print.stream().sorted((I1,I2)->I1.compareTime(I2)).collect(Collectors.toList())) {
				w.writeStartElement("entry");
				
				w.writeStartElement("title");
					w.writeCharacters(this.query);
				w.writeEndElement();

				w.writeStartElement("id");
				w.writeCharacters(md5(image_url.getSrc()));
				w.writeEndElement();

				
				w.writeEmptyElement("link");
				w.writeAttribute("href", 
						image_url.shortcode.isEmpty()?
						this.getUrl()+"?m="+md5(image_url.getSrc()):
						image_url.getPageHref()
						);
				
				w.writeStartElement("updated");
					w.writeCharacters(InstagramToAtom.this.dateFormatter.format(this.date));
				w.writeEndElement();
				
				w.writeStartElement("author");
					w.writeStartElement("name");
						w.writeCharacters(this.query);
					w.writeEndElement();
				w.writeEndElement();
				
				w.writeStartElement("content"); 
				w.writeAttribute("type","xhtml");
				
				w.writeStartElement("div");
				w.writeDefaultNamespace("http://www.w3.org/1999/xhtml");
				w.writeStartElement("p");  
				
				w.writeStartElement("a");
				w.writeAttribute("id",String.valueOf(++ID_GENERATOR));
				w.writeAttribute("target", "_blank");
				w.writeAttribute("href", image_url.shortcode.isEmpty()?this.getUrl()+"?m="+md5(image_url.getSrc()):image_url.getPageHref());
				image_url.write(w);
				w.writeEndElement();//a
				
				w.writeEndElement();//p
				w.writeEndElement();//div
				w.writeEndElement();//content
				
				w.writeEndElement();//entry
				}
			}
		}
		
	
	private String md5(final String s) {
		 MessageDigest md;
		 try {
			 md = MessageDigest.getInstance("MD5");
		 } catch (final Exception err) {
			throw new RuntimeException(err);
		 	}
		md.update(s.getBytes());
		return new BigInteger(1,md.digest()).toString(16);
		}
	
	private File getPreferenceDir() {
		if(this.cacheDirectory==null) {
			final File userDir = new File(System.getProperty("user.home","."));
			this.cacheDirectory =  new File(userDir,".insta2atom");
			}
		return this.cacheDirectory;
		}

	private File getCacheXmlFile() {
		if(this.customCacheFile!=null) return customCacheFile;
		return new File(getPreferenceDir(),"cache.xml");
		}

	
	
	
	
	@Override
	public int doWork(final List<String> args) {
		
		XMLStreamWriter w = null;
		XMLStreamWriter writeCache = null;
		FileOutputStream writeCacheFileWriter = null;
		try
			{			
			final HttpClientBuilder builder = HttpClientBuilder.create();
			final String proxyH = System.getProperty("http.proxyHost");
			final String proxyP = System.getProperty("http.proxyPort");
			if(proxyH!=null && proxyP!=null && 
					!proxyH.trim().isEmpty() && 
					!proxyP.trim().isEmpty())
				{
				builder.setProxy(new HttpHost(proxyH, Integer.parseInt(proxyP)));
				}
			builder.setUserAgent(IOUtils.getUserAgent());
			
			if(this.cookieStoreFile!=null) {
				final BasicCookieStore cookies = CookieStoreUtils.readTsv(this.cookieStoreFile);
				builder.setDefaultCookieStore(cookies);
			}
			
			this.client = builder.build();
					
			
			final File cacheFile = getCacheXmlFile();
			if(!cacheFile.exists())
				{
				LOG.error("Cannot find cache "+cacheFile);
				return -1;
				}
			final File tmpFile = File.createTempFile("insta2atom.",".tmp",cacheFile.getParentFile());
			writeCacheFileWriter = new FileOutputStream(tmpFile);
			
			
			final XMLOutputFactory xof = XMLOutputFactory.newInstance();
			
			
			writeCache = xof.createXMLStreamWriter(writeCacheFileWriter,"UTF-8");
			writeCache.writeStartDocument("UTF-8", "1.0");
			writeCache.writeStartElement("cache");
			writeCache.writeAttribute("date",this.dateFormatter.format(new Date()));
			writeCache.writeCharacters("\n");
			
			
			w = xof.createXMLStreamWriter(System.out, "UTF-8");
			w.writeStartDocument("UTF-8", "1.0");
			w.writeStartElement("feed");
			w.writeAttribute("xmlns", "http://www.w3.org/2005/Atom");
			
			w.writeStartElement("title");
			w.writeCharacters(getClass().getSimpleName());
			w.writeEndElement();
			
			w.writeStartElement("updated");
			w.writeCharacters(this.dateFormatter.format(new Date()));
			w.writeEndElement();
			
			QueryIterator iter = new QueryIterator(cacheFile);
			while(iter.hasNext()) {
				final Query q=iter.next();
				w.writeComment(q.query);
				if(q.query()) 
					{
					q.writeAtom(w);
					}
				q.saveCache(writeCache);
				writeCache.writeCharacters("\n");;
				if(iter.hasNext()) Thread.sleep(this.sleep_seconds*1000);
				}
			iter.close();
			
			w.writeEndElement();
			w.writeEndDocument();
			w.flush();
			w.close();
			
			writeCache.writeEndElement();//cache
			writeCache.writeEndDocument();
			writeCache.flush();
			writeCache.close();
			writeCacheFileWriter.close();
			tmpFile.renameTo(cacheFile);
			this.client.close();this.client=null;
			return 0;
			}
		catch(final Exception err)
			{
			LOG.error(err);
			return -1;
			}
		finally
			{
			IOUtils.close(w);
			IOUtils.close(this.client);
			}
		
		}
	
	public static void main(final String[] args) {
		new InstagramToAtom().instanceMainWithExit(args);
		}

	
}
