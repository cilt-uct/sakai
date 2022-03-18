import { html } from "../assets/lit-element/lit-element.js?version=9c647cc0";
import { ifDefined } from "../assets/lit-html/directives/if-defined.js?version=9c647cc0";
import "../sakai-icon.js?version=9c647cc0";
import "../calendar/sakai-calendar.js?version=9c647cc0";
import "../assets/@lion/calendar/lion-calendar.js?version=9c647cc0";
import { SakaiDashboardWidget } from "./sakai-dashboard-widget.js?version=9c647cc0";
export class SakaiCalendarWidget extends SakaiDashboardWidget {
  constructor() {
    super();
    this.widgetId = "calendar";
    this.title = "Calendar";
    this.loadTranslations("calendar");
  }

  shouldUpdate() {
    return this.siteId || this.userId;
  }

  content() {
    return html`
      <sakai-calendar
        site-id=${ifDefined(this.siteId ? this.siteId : undefined)}
        user-id=${ifDefined(this.userId ? this.userId : undefined)}
      ></sakai-calendar>
    `;
  }

}

if (!customElements.get("sakai-calendar-widget")) {
  customElements.define("sakai-calendar-widget", SakaiCalendarWidget);
}