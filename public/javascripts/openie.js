/*
Contains code for adding Freebase Suggest to the 
OpenIE demo. 
 */

$(document).ready(function() {
  $("#arg1").on('keyup', attachSuggest);
  $("#arg2").on('keyup', attachSuggest);
});

function attachSuggest() {
  var box = $(this)
  var text = box.val().toLowerCase();

  if (text == "type:" || text.indexOf("type:") == 0) {
    if (!box.data("suggest")) {
      // attach suggest and appropriate handlers for type queries
      box.suggest({
        key:'AIzaSyDERIKha5FgaoJPlOIRQMeBz8F6Qbwmtxg',
        filter:'(all type:/type/type with:commons)'
      }).on("fb-select", function(e, data) {
        box.val("type:" + data.name);
      });

      // attach appropriate query handling
      box.data("suggest").request = queryFunction;
    }
  } else if (text == "entity:" || text.indexOf("entity:") == 0) {
    if (!box.data("suggest")) {
      // attach suggest and apprioriate handlers for entity queries
      box.suggest({
        key:'AIzaSyDERIKha5FgaoJPlOIRQMeBz8F6Qbwmtxg',
        filter:'(all (not type:/type/type))'
      }).on("fb-select", function(e, data) {
        box.val("entity:" + data.name);
      });

      // attach appropriate query handling
      box.data("suggest").request = queryFunction;
    }
  } else {
    // if suggest is attached, de-attach it.
    if (box.data("suggest")) {
      box.data("suggest")._destroy();
    }
  }
}

// function defined in suggest.js from freebase suggest
var queryFunction = function(val, cursor) {
  var self = this,
      o = this.options;

  var query = val;

  // modifications to this function so that type and entity queries are done properly.
  if (query.indexOf("type:") == 0) {
    query = query.substring(5, query.length);
  } else if (query.indexOf("entity:") == 0) {
    query = query.substring(7, query.length);
  }

  var filter = o.ac_param.filter || [];

  // SEARCH_PARAMS can be overridden inline
  var extend_ac_param = null;

  if ($.type(filter) === "string") {
      // the original filter may be a single filter param (string)
      filter = [filter];
  }
  // clone original filters so that we don't modify it
  filter = filter.slice();
  if (o.advanced) {
      // parse out additional filters in input value
      var structured = $.suggest.parse_input(query);
      query = structured[0];
      if (structured[1].length) {
          // all advance filters are ANDs
          filter.push("(all " + structured[1].join(" ") + ")");
      }
      extend_ac_param = structured[2];
      if ($.suggest.check_mql_id(query)) {
          // handle anything that looks like a valid mql id
          filter.push("(all mid:\"" + query + "\")");
          query = "";
      }
  }

  var data = {};
  data[o.query_param_name] = query;

  if (cursor) {
    data.cursor = cursor;
  }
  $.extend(data, o.ac_param, extend_ac_param);
  if (filter.length) {
      data.filter = filter;
  }

  var url = o.service_url + o.service_path + "?" + $.param(data, true);
  var cached = $.suggest.cache[url];
  if (cached) {
    this.response(cached, cursor ? cursor : -1, true);
    return;
  }

  clearTimeout(this.request.timeout);

  var ajax_options = {
    url: o.service_url + o.service_path,
    data: data,
    traditional: true,
    beforeSend: function(xhr) {
      var calls = self.input.data("request.count.suggest") || 0;
      if (!calls) {
        self.trackEvent(self.name, "start_session");
      }
      calls += 1;
      self.trackEvent(self.name, "request", "count", calls);
      self.input.data("request.count.suggest", calls);
    },
    success: function(data) {
      $.suggest.cache[url] = data;
      data.prefix = val;  // keep track of prefix to match up response with input value
      self.response(data, cursor ? cursor : -1);
    },
    error: function(xhr) {
      self.status_error();
      self.trackEvent(self.name, "request", "error", {
        url: this.url,
        response: xhr ? xhr.responseText : ''
      });
      self.input.trigger("fb-error", Array.prototype.slice.call(arguments));
    },
    complete: function(xhr) {
      if (xhr) {
        self.trackEvent(self.name, "request", "tid",
        xhr.getResponseHeader("X-Metaweb-TID"));
      }
    },
    dataType: "jsonp",
    cache: true
  };

  this.request.timeout = setTimeout(function() {
    $.ajax(ajax_options);
  }, o.xhr_delay);
}
