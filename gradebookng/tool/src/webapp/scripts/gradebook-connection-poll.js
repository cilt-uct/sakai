/**************************************************************************************
 *                    Connection Poll Javascript                                       
 *************************************************************************************/
function ConnectionPoll($message) {
    this.PING_INTERVAL = 1000*30; // 30 seconds
    this.PING_TIMEOUT = 1000*10; // 10 seconds
    this.PING_URL = "/direct/gbng/ping";

    this._timeout = false;
    this._timeoutCallbacks = [];
    this._recoverCallbacks = [];

    this.$message = $message;

    this.poll();
};


ConnectionPoll.prototype.poll = function() {
  var self = this;

  self._interval = setInterval(function() {
    self.ping();
  }, self.PING_INTERVAL);
};


ConnectionPoll.prototype.ping = function() {
  $.ajax({
    type: "GET",
    url: this.PING_URL,
    data: {
      auto: true // indicate that the request is automatic, not from a user action
    },
    timeout: this.PING_TIMEOUT,
    cache: false,
    success: $.proxy(this.handleSuccess, this),
    error: $.proxy(this.handleTimeout, this)
  });
};

ConnectionPoll.prototype.handleTimeout = function() {
  if (this._timeout) {
    return;
  }

  this._timeout = true;

  this.$message.show();

  this._timeoutCallbacks.forEach(function(callback) {
    callback();
  });
};

ConnectionPoll.prototype.handleSuccess = function() {
  this.$message.hide();

  if (this._timeout) {
    this._recoverCallbacks.forEach(function(callback) {
      callback();
    });
  }

  this._timeout = false;
};

$(document).ready(function() {
  new ConnectionPoll($("#gbConnectionTimeoutFeedback"));
});
