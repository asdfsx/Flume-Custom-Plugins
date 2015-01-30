/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.engine.flume.source;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.event.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BlobHandler for HTTPSource that returns event that contains the request
 * parameters as well as the Binary Large Object (BLOB) uploaded with this
 * request.
 * <p>
 * Note that this approach is not suitable for very large objects because it
 * buffers up the entire BLOB.
 * <p>
 * Example client usage:
 * 
 * <pre>
 * curl --data-binary @sample-statuses-20120906-141433-medium.avro 'http://127.0.0.1:5140?resourceName=sample-statuses-20120906-141433-medium.avro' --header 'Content-Type:application/octet-stream' --verbose
 * </pre>
 */
public class AllParamHandler implements HTTPSourceHandler {

	private int maxBlobLength = MAX_BLOB_LENGTH_DEFAULT;

	public static final String MAX_BLOB_LENGTH_KEY = "maxBlobLength";
	public static final int MAX_BLOB_LENGTH_DEFAULT = 100 * 1000 * 1000;

	private static final int DEFAULT_BUFFER_SIZE = 1024 * 8;
	private static final Logger LOGGER = LoggerFactory
			.getLogger(AllParamHandler.class);

	public AllParamHandler() {
	}

	@Override
	public void configure(Context context) {
		this.maxBlobLength = context.getInteger(MAX_BLOB_LENGTH_KEY,
				MAX_BLOB_LENGTH_DEFAULT);
		if (this.maxBlobLength <= 0) {
			throw new ConfigurationException("Configuration parameter "
					+ MAX_BLOB_LENGTH_KEY + " must be greater than zero: "
					+ maxBlobLength);
		}
	}

  	@SuppressWarnings("resource")
	@Override
	public List<Event> getEvents(HttpServletRequest request) throws Exception {
		Map<String, String> contents = getHeaders(request);
		String eventbody = createbody(contents);
		Event event = EventBuilder.withBody(eventbody, Charset.forName("UTF-8"));
		LOGGER.debug("blobEvent: {}", event);
		return Collections.singletonList(event);
	}
	
//	@SuppressWarnings("resource")
//	  @Override
//	  public List<Event> getEvents(HttpServletRequest request) throws Exception {
//	    Map<String, String> headers = getHeaders(request);    
//	    InputStream in = request.getInputStream();
//	    try {
//	      ByteArrayOutputStream blob = null;
//	      byte[] buf = new byte[Math.min(maxBlobLength, DEFAULT_BUFFER_SIZE)];
//	      int blobLength = 0;
//	      int n = 0;
//	      while ((n = in.read(buf, 0, Math.min(buf.length, maxBlobLength - blobLength))) != -1) {
//	        if (blob == null) {
//	          blob = new ByteArrayOutputStream(n);
//	        }
//	        blob.write(buf, 0, n);
//	        blobLength += n;
//	        if (blobLength >= maxBlobLength) {
//	          LOGGER.warn("Request length exceeds maxBlobLength ({}), truncating BLOB event!", maxBlobLength);
//	          break;
//	        }
//	      }
//
//	      byte[] array = blob != null ? blob.toByteArray() : new byte[0];
//	      Event event = EventBuilder.withBody(array, headers);
//	      LOGGER.debug("blobEvent: {}", event);
//	      return Collections.singletonList(event);
//	    } finally {
//	      in.close();
//	    }
//	  }
	
	private String createbody(Map<String,String> content){
		StringBuffer sb = new StringBuffer();
		sb.append("{");
		for(String key:content.keySet()){
			sb.append("\"").append(key).append("\":\"").append(content.get(key)).append("\",");
		}
		sb.append("}");
		return sb.toString();
	}

	private Map<String, String> getHeaders(HttpServletRequest request) {
		if (LOGGER.isDebugEnabled()) {
			Map requestHeaders = new HashMap();
			Enumeration iter = request.getHeaderNames();
			while (iter.hasMoreElements()) {
				String name = (String) iter.nextElement();
				requestHeaders.put(name, request.getHeader(name));
			}
			LOGGER.debug("requestHeaders: {}", requestHeaders);
		}
		
		long tl = System.currentTimeMillis(); 
		
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("tstamp", Long.toString(tl));
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie c : cookies) {
				String cname = c.getName();
				String cvalue = c.getValue();
				headers.put("cookie_" + cname, cvalue);
			}
		}

		Enumeration iter = request.getHeaderNames();
		while (iter.hasMoreElements()) {
			String hname = (String) iter.nextElement();
			headers.put("header_" + hname, request.getHeader(hname));
		}

		String requesturi = request.getRequestURI();
		headers.put("requesturi", requesturi);

		String querystring = request.getQueryString();
		headers.put("querystring", querystring);

		return headers;
	}
}
