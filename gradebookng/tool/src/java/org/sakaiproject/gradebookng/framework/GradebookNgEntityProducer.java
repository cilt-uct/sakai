/**
 * Copyright (c) 2003-2016 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.gradebookng.framework;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.sakaiproject.component.cover.HotReloadConfigurationService;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.EntityTransferrer;
import org.sakaiproject.entity.api.HttpAccess;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.gradebookng.business.GradebookNgBusinessService;
import org.sakaiproject.gradebookng.business.model.GbGradeInfo;
import org.sakaiproject.gradebookng.business.model.GbStudentGradeInfo;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.service.gradebook.shared.CategoryDefinition;
import org.sakaiproject.service.gradebook.shared.CourseGrade;
import org.sakaiproject.service.gradebook.shared.GradeMappingDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookFrameworkService;
import org.sakaiproject.service.gradebook.shared.GradebookInformation;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.ToolConfiguration;
import org.sakaiproject.thread_local.api.ThreadLocalManager;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import lombok.Setter;

/**
 * Entity Producer for GradebookNG. This is required to participate in other entity actions but also handles the transfer of data between
 * sites
 */
public class GradebookNgEntityProducer implements EntityProducer, EntityTransferrer {

	protected static final String[] TOOL_IDS = { "sakai.gradebookng" };

	protected final static String LABEL = "GradebookNG";
	protected final static String referenceRoot = "/gradebookng";

	/**
	 * These are shared with the GradebookNgContextObserver
	 */
	@Setter
	protected EntityManager entityManager;

	@Setter
	protected GradebookService gradebookService;

	@Setter
	protected GradebookFrameworkService gradebookFrameworkService;

	@Setter
	protected GradebookNgBusinessService gradebookNgBusinessService;

	@Setter
	protected SiteService siteService;

	@Setter
	protected ThreadLocalManager threadLocalManager;

	protected final static String CURRENT_PLACEMENT = "sakai:ToolComponent:current.placement";
	protected final static String CURRENT_TOOL = "sakai:ToolComponent:current.tool";

	/**
	 * Register this class as an EntityProducer.
	 */
	public void init() {
		this.entityManager.registerEntityProducer(this, referenceRoot);
	}

	@Override
	public String getLabel() {
		return LABEL;
	}

	@Override
	public boolean willArchiveMerge() {
		return true;
	}

	@Override
	public String archive(final String siteId, final Document doc, final Stack<Element> stack,
			final String archivePath, final List<Reference> attachments) {

		Site site = null;
		try {
			site = siteService.getSite(siteId);
		} catch(IdUnusedException e) {
			return "ERROR: site does not exist";
		}

		ToolConfiguration tool = site.getToolForCommonId("sakai.gradebookng");
		if (tool == null) {
			return "ERROR: Tool sakai.gradebookng not found in site=" + siteId;
		}

		threadLocalManager.set(CURRENT_PLACEMENT, tool);
		threadLocalManager.set(CURRENT_TOOL, tool.getTool());

		DateFormat dateFormat = new SimpleDateFormat("yyyy-mm-dd");

		StringBuilder result = new StringBuilder();
		result.append("archiving ").append(getLabel()).append("\n");

		// <GradebookNG>
		Element root = doc.createElement(getLabel());

		// <GradebookConfig>
		Element gradebookConfigEl = doc.createElement("GradebookConfig");
		Gradebook gradebook = null;
		try {
			gradebook = (Gradebook) this.gradebookService.getGradebook(siteId);
		} catch (GradebookNotFoundException e) {
			return "ERROR: Gradebook not found in site";
		}

		GradebookInformation settings = this.gradebookService.getGradebookInformation(gradebook.getUid());
		List<GradeMappingDefinition> gradeMappings = settings.getGradeMappings();
		String configuredGradeMappingId = settings.getSelectedGradeMappingId();
		GradeMappingDefinition configuredGradeMapping = gradeMappings.stream()
				.filter(gradeMapping -> StringUtils.equals(gradeMapping.getId(), configuredGradeMappingId))
				.findFirst()
				.get();

		Map<String, Double> gradeMap = settings.getSelectedGradingScaleBottomPercents();

		Element gradeMappingsEl = doc.createElement("GradeMappings");
		gradeMappingsEl.setAttribute("name", configuredGradeMapping.getName());
		for (Map.Entry<String, Double> entry : gradeMap.entrySet()) {
			Element gradeMappingEl = doc.createElement("GradeMapping");
			gradeMappingEl.setAttribute("letterGrade", entry.getKey());
			gradeMappingEl.setAttribute("bottomPercentage", String.valueOf(entry.getValue()));
			gradeMappingsEl.appendChild(gradeMappingEl);
		}

		gradebookConfigEl.appendChild(gradeMappingsEl);

		Element courseGradeDisplayedEl = doc.createElement("CourseGradeDisplayed");
		courseGradeDisplayedEl.setTextContent(String.valueOf(settings.isCourseGradeDisplayed()));
		gradebookConfigEl.appendChild(courseGradeDisplayedEl);

		Element courseLetterGradeDisplayedEl = doc.createElement("CourseLetterGradeDisplayed");
		courseLetterGradeDisplayedEl.setTextContent(String.valueOf(settings.isCoursePointsDisplayed()));
		gradebookConfigEl.appendChild(courseLetterGradeDisplayedEl);

		Element coursePointsDisplayedEl = doc.createElement("CoursePointsDisplayed");
		coursePointsDisplayedEl.setTextContent(String.valueOf(settings.isCoursePointsDisplayed()));
		gradebookConfigEl.appendChild(coursePointsDisplayedEl);

		Element totalPointsDisplayedEl = doc.createElement("TotalPointsDisplayed");
		totalPointsDisplayedEl.setTextContent(String.valueOf(settings.isCoursePointsDisplayed()));
		gradebookConfigEl.appendChild(totalPointsDisplayedEl);

		Element courseAverageDisplayedEl = doc.createElement("CourseAverageDisplayed");
		courseAverageDisplayedEl.setTextContent(String.valueOf(settings.isCourseAverageDisplayed()));
		gradebookConfigEl.appendChild(courseAverageDisplayedEl);

		Element categoryTypeEl = doc.createElement("CategoryType");
		String categoryCode = null;
		if (settings.getCategoryType() == 1) {
			categoryCode = "NO_CATEGORIES";
		} else if (settings.getCategoryType() == 2) {
			categoryCode = "CATEGORIES_APPLIED";
		} else if (settings.getCategoryType() == 3) {
			categoryCode = "WEIGHTED_CATEGORIES_APPLIED";
		} else {
			categoryCode = "UNKNOWN";
		}
		categoryTypeEl.setTextContent(categoryCode);
		gradebookConfigEl.appendChild(categoryTypeEl);

		Element gradeTypeEl = doc.createElement("GradeType");
		String gradeTypeCode = null;
		switch(settings.getGradeType()) {
			case 1:
				gradeTypeCode = "POINTS";
				break;
			case 2:
				gradeTypeCode = "PERCENTAGE";
				break;
			case 3:
				gradeTypeCode = "LETTER";
				break;
			default:
				gradeTypeCode = "UNKNOWN";
		}
		gradeTypeEl.setTextContent(gradeTypeCode);
		gradebookConfigEl.appendChild(gradeTypeEl);

		if (settings.getCategoryType() > 1) {
			Element categoriesEl = doc.createElement("categories");
			for (CategoryDefinition category : settings.getCategories()) {
				Element categoryEl = doc.createElement("category");
				categoryEl.setAttribute("id", String.valueOf(category.getId()));
				categoryEl.setAttribute("name", category.getName());
				categoryEl.setAttribute("extraCredit", String.valueOf(category.isExtraCredit()));
				if (settings.getCategoryType() == 3) {
					categoryEl.setAttribute("weight", String.valueOf(category.getWeight()));
				} else {
					categoryEl.setAttribute("weight", "");
				}
				categoryEl.setAttribute("dropLowest", String.valueOf(category.getDropLowest()));
				categoryEl.setAttribute("dropHighest", String.valueOf(category.getDropHighest()));
				categoryEl.setAttribute("keepHighest", String.valueOf(category.getKeepHighest()));
				categoryEl.setAttribute("order", String.valueOf(category.getCategoryOrder()));
				categoriesEl.appendChild(categoryEl);
			}
			gradebookConfigEl.appendChild(categoriesEl);
		}

		root.appendChild(gradebookConfigEl);

		// <GradebookItems>
		List<Assignment> gradebookItems = this.gradebookNgBusinessService.getGradebookAssignments(siteId);

		// apply external app blacklist
		List<String> blacklistedExternalAppNames = Arrays.asList(HotReloadConfigurationService.getString("gradebookng.archive.external_app_name.blacklist", "").split(","));
		gradebookItems = gradebookItems.stream().filter(item -> {
			return !item.isExternallyMaintained() || !blacklistedExternalAppNames.contains(item.getExternalAppName());
		}).collect(Collectors.toList());

//		Disable whitelist.
//		// apply external app whitelist
//		List<String> whitelistedExternalAppNames = Arrays.asList(ServerConfigurationService.getString("gradebookng.archive.external_app_name.whitelist", "").split(","));
//		gradebookItems = gradebookItems.stream().filter(item -> {
//			return !item.isExternallyMaintained() || whitelistedExternalAppNames.contains(item.getExternalAppName());
//		}).collect(Collectors.toList());

		Element gradebookItemsEl = doc.createElement("GradebookItems");
		for (Assignment gradebookItem : gradebookItems) {
			Element gradebookItemEl = doc.createElement("GradebookItem");
			gradebookItemEl.setAttribute("id", String.valueOf(gradebookItem.getId()));
			gradebookItemEl.setAttribute("name", gradebookItem.getName());
			gradebookItemEl.setAttribute("points", String.valueOf(gradebookItem.getPoints()));
			if (gradebookItem.getDueDate() == null) {
				gradebookItemEl.setAttribute("dueDate", "");
			} else {
				gradebookItemEl.setAttribute("dueDate", dateFormat.format(gradebookItem.getDueDate()));
			}
			gradebookItemEl.setAttribute("countedInCourseGrade", String.valueOf(gradebookItem.isCounted()));
			gradebookItemEl.setAttribute("externallyMaintained", String.valueOf(gradebookItem.isExternallyMaintained()));
			gradebookItemEl.setAttribute("externalAppName", gradebookItem.getExternalAppName());
			gradebookItemEl.setAttribute("externalId", gradebookItem.getExternalId());
			gradebookItemEl.setAttribute("releasedToStudent", String.valueOf(gradebookItem.isReleased()));
			if (gradebookItem.getCategoryId() == null) {
				gradebookItemEl.setAttribute("categoryId", "");
			} else {
				gradebookItemEl.setAttribute("categoryId", String.valueOf(gradebookItem.getCategoryId()));
			}
			gradebookItemEl.setAttribute("extraCredit", String.valueOf(gradebookItem.getExtraCredit()));
			gradebookItemEl.setAttribute("order", String.valueOf(gradebookItem.getSortOrder()));
			gradebookItemEl.setAttribute("categorizedOrder", String.valueOf(gradebookItem.getCategorizedSortOrder()));
			gradebookItemsEl.appendChild(gradebookItemEl);
		}
		root.appendChild(gradebookItemsEl);

		stack.peek().appendChild(root);

		return result.toString();
	}

	@Override
	public String merge(final String siteId, final Element root, final String archivePath,
			final String fromSiteId, final Map<String, String> attachmentNames,
			final Map<String, String> userIdTrans, final Set<String> userListAllowImport) {
		return "GradebookNG merge not supported: nothing to do.";
	}

	@Override
	public boolean parseEntityReference(final String reference, final Reference ref) {
		return false;
	}

	@Override
	public String getEntityDescription(final Reference ref) {
		return null;
	}

	@Override
	public ResourceProperties getEntityResourceProperties(final Reference ref) {
		return null;
	}

	@Override
	public Entity getEntity(final Reference ref) {
		return null;
	}

	@Override
	public String getEntityUrl(final Reference ref) {
		return null;
	}

	@Override
	public Collection<String> getEntityAuthzGroups(final Reference ref, final String userId) {
		return null;
	}

	@Override
	public HttpAccess getHttpAccess() {
		return null;
	}

	@Override
	public String[] myToolIds() {
		return TOOL_IDS;
	}

	/**
	 * Handle import via merge
	 */
	@Override
	public void transferCopyEntities(final String fromContext, final String toContext, final List<String> ids) {

		final Gradebook gradebook = (Gradebook) this.gradebookService.getGradebook(fromContext);

		final GradebookInformation gradebookInformation = this.gradebookService.getGradebookInformation(gradebook.getUid());

		final List<Assignment> assignments = this.gradebookService.getAssignments(fromContext);

		this.gradebookService.transferGradebook(gradebookInformation, assignments, toContext);
	}

	/**
	 * Handle import via replace
	 */
	@Override
	public void transferCopyEntities(final String fromContext, final String toContext, final List<String> ids, final boolean cleanup) {

		if (cleanup == true) {

			final Gradebook gradebook = (Gradebook) this.gradebookService.getGradebook(toContext);

			// remove assignments in 'to' site
			final List<Assignment> assignments = this.gradebookService.getAssignments(gradebook.getUid());
			assignments.forEach(a -> this.gradebookService.removeAssignment(a.getId()));

			// remove categories in 'to' site
			final List<CategoryDefinition> categories = this.gradebookService.getCategoryDefinitions(gradebook.getUid());
			categories.forEach(c -> this.gradebookService.removeCategory(c.getId()));
		}

		// now migrate
		this.transferCopyEntities(fromContext, toContext, ids);

	}

}
