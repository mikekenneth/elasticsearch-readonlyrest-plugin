package org.elasticsearch.plugin.readonlyrest.acl;

import com.google.common.base.Joiner;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.aliases.IndexAliasesService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  private final static ESLogger logger = Loggers.getLogger(RequestContext.class);
  /*
    * A regular expression to match the various representations of "localhost"
    */
  private final static Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");

  private final static String LOCALHOST = "127.0.0.1";

  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private Set<String> indices = null;
  private String content = null;
  IndicesService indexService = null;

  @Inject
  public RequestContext(@Assisted RestChannel channel, @Assisted RestRequest request, @Assisted String action, @Assisted ActionRequest actionRequest, IndicesService indicesService) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.indexService = indicesService;
  }

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest) {
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
  }

  public String getRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (localhostRe.matcher(remoteHost).find()) {
      remoteHost = LOCALHOST;
    }
    return remoteHost;
  }

  public String getContent() {
    if (content == null) {
      try {
        content = new String(request.content().array());
      } catch (Exception e) {
        content = "<not available>";
      }
    }
    return content;
  }

  public Set<String> getAvailableIndicesAndAliases() {
    final HashSet<String> harvested = new HashSet<>();
    final Iterator<IndexService> i = indexService.iterator();
    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            while (i.hasNext()) {
              IndexService theIndexSvc = i.next();
              harvested.add(theIndexSvc.index().getName());
              final IndexAliasesService aliasSvc = theIndexSvc.aliasesService();
              try {
                Field field = aliasSvc.getClass().getDeclaredField("aliases");
                field.setAccessible(true);
                ImmutableOpenMap<String, String> aliases = (ImmutableOpenMap<String, String>) field.get(aliasSvc);
                System.out.printf(aliases.toString());
                for (Object o : aliases.keys().toArray()) {
                  String a = (String) o;
                  harvested.add(a);
                }
                //  harvested.addAll(aliases.keys().toArray(new String[aliases.keys().size()]));
              } catch (NoSuchFieldException e) {
                e.printStackTrace();
              } catch (IllegalAccessException e) {
                e.printStackTrace();
              }
            }
            return null;
          }
        });
    return harvested;
  }

  public void setIndices(final Set<String> newIndices) {
    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            try {
              Field field = actionRequest.getClass().getDeclaredField("indices");
              field.setAccessible(true);
              String[] idxArray = newIndices.toArray(new String[newIndices.size()]);
              field.set(actionRequest, idxArray);
            } catch (NoSuchFieldException e) {
              e.printStackTrace();
            } catch (IllegalAccessException e) {
              e.printStackTrace();
            }
            indices.clear();
            indices.addAll(newIndices);
            return null;
          }
        });
  }

  public Set<String> getIndices() {
    if (indices != null) {
      return indices;
    }

    final String[][] out = {new String[1]};
    AccessController.doPrivileged(
        new PrivilegedAction<Void>() {
          @Override
          public Void run() {
            String[] indices = new String[0];
            ActionRequest ar = actionRequest;

            if (ar instanceof CompositeIndicesRequest) {
              CompositeIndicesRequest cir = (CompositeIndicesRequest) ar;
              for (IndicesRequest ir : cir.subRequests()) {
                indices = ObjectArrays.concat(indices, ir.indices(), String.class);
              }
            } else {
              try {
                Method m = ar.getClass().getMethod("indices");
                if (m.getReturnType() != String[].class) {
                  out[0] = new String[]{};
                  return null;
                }
                m.setAccessible(true);
                indices = (String[]) m.invoke(ar);
              } catch (SecurityException e) {
                logger.error("Can't get indices for request: " + toString());
                throw new SecurityPermissionException("Insufficient permissions to extract the indices. Abort! Cause: " + e.getMessage(), e);
              } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                logger.debug("Failed to discover indices associated to this request: " + this);
              }
            }

            if (indices == null) {
              indices = new String[0];
            }

            HashSet<String> tempSet = new HashSet<>(Arrays.asList(indices));


            // Finding indices from payload and deduping them
            if (actionRequest instanceof SearchRequest) {

              // Translate any wildcards in the request into real indices (easier to reason about in indices rule)
              MatcherWithWildcards realIndexMatcher = new MatcherWithWildcards(tempSet);
              for (String realIdx : getAvailableIndicesAndAliases()) {
                if (realIndexMatcher.match(realIdx)) {
                  tempSet.add(realIdx);
                }
              }

              // Hack #FIXME
              if (tempSet.size() == 0 || tempSet.contains("_all") || tempSet.contains("_search")) {
                tempSet.clear();
                tempSet.add("_all");
              }
            }

            // DONE
            indices = tempSet.toArray(new String[tempSet.size()]);

            if (logger.isDebugEnabled()) {
              String idxs = Joiner.on(',').skipNulls().join(indices);
              logger.debug("Discovered indices: " + idxs);
            }

            out[0] = indices;
            return null;
          }
        }
    );

    indices = Sets.newHashSet(out[0]);

    return indices;
  }

  private HashSet<String> getIndicesFromSearchDSLPayload(String[] indices) {
    XContentParser parser = null;
    HashSet<String> harvested = new HashSet<>();
    harvested.addAll(Arrays.asList(indices));
    try {
      BytesReference bytes = ((SearchRequest) actionRequest).source();
      parser = XContentFactory.xContent(bytes).createParser(bytes);
      Map m = parser.map();

      if (m.containsKey("indices")) {
        Map outerIndicesQuery = (Map) m.get("indices");
        if (outerIndicesQuery != null) {
          String singleIndex = (String) outerIndicesQuery.get("index");
          if (!ConfigurationHelper.isNullOrEmpty(singleIndex)) {
            harvested.add(singleIndex);
          }
          if (outerIndicesQuery.containsKey("indices")) {
            List<String> listOfIndices = (List<String>) outerIndicesQuery.get("indices");
            harvested.addAll(listOfIndices);
          }
        }
      }
      System.out.println(harvested.size());
    } catch (Exception e) {
      logger.debug("cannot find any index in the payload");
    } finally {
      if (parser != null) {
        parser.close();
      }
    }
    return harvested;
  }

  public RestChannel getChannel() {
    return channel;
  }

  public RestRequest getRequest() {
    return request;
  }

  public String getAction() {
    return action;
  }

  public ActionRequest getActionRequest() {
    return actionRequest;
  }

  @Override
  public String toString() {
    StringBuilder idxsb = new StringBuilder();
    idxsb.append("[");
    try {
      for (String i : getIndices()) {
        idxsb.append(i).append(' ');
      }
    } catch (Exception e) {
      idxsb.append("<CANNOT GET INDICES>");
    }
    String idxs = idxsb.toString().trim() + "]";
    return "{ action: " + action +
        ", OA:" + getRemoteAddress() +
        ", indices:" + idxs +
        ", M:" + request.method() +
        ", P:" + request.path() +
        ", C:" + getContent() +
        ", Headers:" + request.getHeaders() +
        "}";
  }

}
