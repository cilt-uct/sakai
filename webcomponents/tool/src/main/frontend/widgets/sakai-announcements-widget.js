import { html } from "../assets/lit-element/lit-element.js?version=9c647cc0";
import { ifDefined } from "../assets/lit-html/directives/if-defined.js?version=9c647cc0";
import "../announcements/sakai-announcements.js?version=9c647cc0";
import { SakaiDashboardWidget } from "./sakai-dashboard-widget.js?version=9c647cc0";
export class SakaiAnnouncementsWidget extends SakaiDashboardWidget {
  constructor() {
    super();
    this.widgetId = "announcements";
    this.widgetId = "announcements";
    this.loadTranslations("announcements");
  }

  content() {
    return html`
      <sakai-announcements
        user-id="${ifDefined(this.userId ? this.userId : undefined)}"
        site-id="${ifDefined(this.siteId ? this.siteId : undefined)}"
      >
    `;
  }

}

if (!customElements.get("sakai-announcements-widget")) {
  customElements.define("sakai-announcements-widget", SakaiAnnouncementsWidget);
}