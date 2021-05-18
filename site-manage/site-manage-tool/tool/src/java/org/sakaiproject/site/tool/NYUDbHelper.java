package org.sakaiproject.site.tool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.user.cover.UserDirectoryService;
import org.sakaiproject.component.cover.HotReloadConfigurationService;

/**
 * NYUDbHelper abstracts the DB calls to the additional NYU_ specific DB tables.
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 * @author Mark Triggs (mark.triggs@nyu.edu)
 *
 */

public class NYUDbHelper {

	String DEFAULT_KEY = "DEFAULT";

	private SqlService sqlService;
	private static Log M_log = LogFactory.getLog(NYUDbHelper.class);
	
	public NYUDbHelper() {
		if(sqlService == null) {
			sqlService = (SqlService) org.sakaiproject.component.cover.ComponentManager.get("org.sakaiproject.db.api.SqlService");
		}
	}
	
	protected String findSponsor(String sectionEid) {

		try {
			Connection db = sqlService.borrowConnection();

			try {
				PreparedStatement ps = db.prepareStatement("select sponsor_course " +
						"from NYU_T_CROSSLISTINGS " +
						"where nonsponsor_course = ?");
				ps.setString(1, sectionEid.replace("_", ":"));

				ResultSet rs = ps.executeQuery();
				try {
					if (rs.next()) {
						return rs.getString(1).replace(":", "_");
					}
				} finally {
					rs.close();
				}
			} finally {
				sqlService.returnConnection(db);
			}
		} catch (SQLException e) {
			M_log.warn(this + ".findSponsor: " + e);
		}
		return null;
	}
	
	/**
	 * Get the description value for the section
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteDescription(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "descrlong");	
	}
	
	/*
	 "Department", "School", "Location
	 c.acad_org as department
	 c.acad_group as school
	 c.campus as location
	 */
	
	/**
	 * Get the short description value for the site
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteShortDescription(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "descr");
	}
	
	/**
	 * Get the department value for the site
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteDepartment(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "acad_org");
	}
	
	/**
	 * Get the school value for the site
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteSchool(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "acad_group");
	}
	
	/**
	 * Get the location/campus for the site
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteLocation(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "location");
	}

	/**
	 * Get the subject code for the site
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteSubject(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "subject");
	}

	/**
	 * Get the instruction mode for the site (e.g. online, in person, hybrid)
	 * @param sectionEid
	 * @return
	 */
	protected String getSiteInstructionMode(String sectionEid) {
		return getPropertyFromCourseCatalog(sectionEid, "instruction_mode");
	}

	protected String getTermEid(String sectionEid) {
		String result = getTerm(sectionEid);

		if (result == null) {
			return null;
		} else {
			return result.replace(" ", "_");
		}
	}

	protected String getTerm(String sectionEid) {
		try {
			Connection db = sqlService.borrowConnection();

			try {
				PreparedStatement ps = db.prepareStatement("select s.descr from nyu_t_acad_session s inner join nyu_t_course_catalog cc on cc.strm = s.strm AND s.acad_career = cc.acad_career where cc.stem_name = ?");
				ps.setString(1, sectionEid.replace("_", ":"));

				ResultSet rs = ps.executeQuery();
				try {
					if (rs.next()) {
						return rs.getString(1);
					}
				} finally {
					rs.close();
				}
			} finally {
				sqlService.returnConnection(db);
			}
		} catch (SQLException e) {
			M_log.warn(this + ".getTerm: " + e);
		}

		return null;
	}

	/**
	 * Helper to do the DB calls for us onto nyu_t_course_catalog table, given a sectionEid and a column name. 
	 * The sectionEid is the stem_name with separators replaced.
	 * @param sectionEid
	 * @param columnName
	 * @return
	 */
	private String getPropertyFromCourseCatalog(String sectionEid, String columnName) {
		try {
			Connection db = sqlService.borrowConnection();

			try {
				PreparedStatement ps = db.prepareStatement("select " + columnName + " from NYU_T_COURSE_CATALOG where stem_name = ?");
				ps.setString(1, sectionEid.replace("_", ":"));

				ResultSet rs = ps.executeQuery();
				try {
					if (rs.next()) {
						return rs.getString(1);
					}
				} finally {
					rs.close();
				}
			} finally {
				sqlService.returnConnection(db);
			}
		} catch (SQLException e) {
			M_log.warn(this + ".getPropertyFromCourseCatalog: " + e);
		}
		return null;	
	}
	

	protected String schoolCodeLookup(String schoolCode) {
		try {
			Connection db = sqlService.borrowConnection();

			try {
				PreparedStatement ps = db.prepareStatement("SELECT * FROM nyu_t_site_templates WHERE school_code = ?");
				ps.setString(1, schoolCode);

				ResultSet rs = ps.executeQuery();
				try {
					while (rs.next()) {
						try {
							if (rs.getString("subject") != null) {
								// We're not interested in matches on rows that are scoped to a subject.
								//
								// In a perfect world we'd add this clause to the above query, but want
								// to support schemas that haven't had the subject column added yet.
								continue;
							}
						} catch (SQLException e) {
							// If there is no subject column, this is our match
						}

						return rs.getString("template_site_id");
					}
				} finally {
					rs.close();
				}
			} finally {
				sqlService.returnConnection(db);
			}
		} catch (SQLException e) {
			M_log.warn(this + ".getSiteTemplateForSchoolCode: " + e);
		}

		return null;
	}


	protected String getSiteTemplateForSchoolCode(String schoolCode, String termCode) {

		if (StringUtils.isBlank(schoolCode)) {
			return schoolCodeLookup(DEFAULT_KEY);
		}

		String result = null;

		result = schoolCodeLookup(schoolCode);

		if (result == null) {
		    result = schoolCodeLookup(DEFAULT_KEY);
		}

		M_log.info("Selected template for school " + schoolCode + " and term " + termCode + ": " + result);

		return result;
	}


	protected String getSiteTemplateForSchoolCodeAndSubject(String schoolCode, String subjectCode, String termCode) {
		if (StringUtils.isBlank(schoolCode) || StringUtils.isBlank(subjectCode)) {
			return null;
		}

		try {
			Connection db = sqlService.borrowConnection();

			try {
				PreparedStatement ps = db.prepareStatement("SELECT template_site_id FROM nyu_t_site_templates WHERE school_code = ? AND subject = ?");
				ps.setString(1, schoolCode);
				ps.setString(2, subjectCode);

				ResultSet rs = ps.executeQuery();
				try {
					if (rs.next()) {
						return rs.getString(1);
					}
				} finally {
					rs.close();
				}
			} finally {
				sqlService.returnConnection(db);
			}
		} catch (SQLException e) {
			M_log.warn(this + ".getSiteTemplateForSchoolCodeAndSubject: " + e);
		}

		return null;
	}


	protected boolean isCurrentUserDental() {
		Connection db = null;
		try {
			db = sqlService.borrowConnection();

			String netid = UserDirectoryService.getCurrentUser().getEid();
			String dentalSchoolCodes = HotReloadConfigurationService.getString("nyu.dental-school-codes", "'DN', 'UD', 'CD'");

			String[] queries = new String[] {
				String.format("SELECT count(1) " +
					      " from NYU_T_INSTRUCTORS i " +
					      " inner join NYU_T_COURSE_CATALOG cc on cc.stem_name = i.stem_name AND cc.acad_group in (%s)" +
					      " where i.instr_role IN ('12', 'PI', '11') AND i.netid = ?",
					      dentalSchoolCodes),

				String.format("SELECT count(1) " +
					      " from NYU_T_COURSE_ADMINS a " +
					      " inner join NYU_T_COURSE_CATALOG cc on cc.stem_name = a.stem_name AND cc.acad_group in (%s)" +
					      " where a.netid = ?",
					      dentalSchoolCodes),
			};


			PreparedStatement ps;
			for (String query : queries) {
				ps = db.prepareStatement(query);
				ps.setString(1, netid);

				ResultSet rs = null;
				try {
					rs = ps.executeQuery();
					if (rs.next()) {
						if (rs.getInt(1) > 0) {
							return true;
						}
					}
				} finally {
					if (rs != null) { rs.close(); }
					if (ps != null) { ps.close(); }
				}
			}
		} catch (SQLException e) {
			M_log.warn(this + ".isCurrentUserDental: " + e);
		} finally {
			sqlService.returnConnection(db);
		}

		return false;
	}

	public List<String> getReallyAvailableSections(List<String> sectionEids) {
		Map<String, Integer> counts = new HashMap<>();

		for (String eid : sectionEids) {
			counts.put(eid, 0);
		}

		Connection db = null;

		try {
			db = sqlService.borrowConnection();

			String placeholders = sectionEids.stream().map(e -> "?").collect(Collectors.joining(", "));

			String sitesQuery = String.format("SELECT srp.PROVIDER_ID " +
							" from SAKAI_REALM_PROVIDER srp " +
							" inner join SAKAI_REALM sr on sr.REALM_KEY = srp.REALM_KEY " +
							" inner join SAKAI_SITE ss on CONCAT('/site/', ss.SITE_ID) = sr.REALM_ID " +
							" where srp.PROVIDER_ID in (%s)",
					placeholders);

			String colabSitesQuery = String.format("SELECT srp.PROVIDER_ID, TO_CHAR(ssp.VALUE) as IS_COLAB_SITE" +
							" from SAKAI_REALM_PROVIDER srp " +
							" inner join SAKAI_REALM sr on sr.REALM_KEY = srp.REALM_KEY " +
							" inner join SAKAI_SITE ss on CONCAT('/site/', ss.SITE_ID) = sr.REALM_ID " +
							" inner join SAKAI_SITE_PROPERTY ssp on ssp.SITE_ID = ss.SITE_ID AND ssp.NAME = 'collaborative_site'" +
							" where srp.PROVIDER_ID in (%s)",
					placeholders);


			PreparedStatement ps;
			ps = db.prepareStatement(sitesQuery);

			for (int i=0; i<sectionEids.size(); i++) {
				ps.setString(i+1, sectionEids.get(i));
			}

			ResultSet rs = null;
			try {
				rs = ps.executeQuery();
				while (rs.next()) {
					String eid = rs.getString("PROVIDER_ID");
					counts.put(eid, counts.get(eid) + 1);
				}
			} finally {
				if (rs != null) { rs.close(); }
				if (ps != null) { ps.close(); }
			}


			ps = db.prepareStatement(colabSitesQuery);

			for (int i=0; i<sectionEids.size(); i++) {
				ps.setString(i+1, sectionEids.get(i));
			}

			rs = null;
			try {
				rs = ps.executeQuery();
				while (rs.next()) {
					String eid = rs.getString("PROVIDER_ID");
					counts.put(eid, counts.get(eid) - 1);
				}
			} finally {
				if (rs != null) { rs.close(); }
				if (ps != null) { ps.close(); }
			}

		} catch (SQLException e) {
			M_log.warn(this + ".getReallyAvailableSections: " + e);
		} finally {
			sqlService.returnConnection(db);
		}

		List<String> result = new ArrayList<>();

		for (String eid : counts.keySet()) {
			if (counts.get(eid) == 0) {
				result.add(eid);
			}
		}

		return result;
	}

	private boolean equalsAndNonNull(String val1, String val2) {
		if (val1 == null || val2 == null) {
			return false;
		}

		return val1.equals(val2);
	}

	public List<String> getMatchedProviderIds(String termEid, List<String> providerIds, String action) {
		List<String> result = new ArrayList<>();

		Connection db = null;
		try {
			db = sqlService.borrowConnection();

			String placeholders = providerIds.stream().map(e -> "?").collect(Collectors.joining(", "));

			// Select candidate rosters in our set that MIGHT match one of the relevant
			// blocked roster rules.  This is a coarse first-pass query that will pull back
			// some false positives, and we'll whittle down the list in code momentarily.
			PreparedStatement ps = db.prepareStatement("select" +
								   "   br.strm as rule_strm," +
								   "   br.acad_org as rule_department," +
								   "   br.acad_group as rule_school," +
								   "   br.stem_name as rule_stem_name," +
								   "   cc.strm as roster_strm," +
								   "   cc.acad_org as roster_department," +
								   "   cc.acad_group as roster_school," +
								   "   cc.stem_name as roster_stem_name," +
								   "   replace(cc.stem_name, ':', '_') as roster_id" +
								   " from nyu_t_course_catalog cc" +
								   " inner join nyu_t_blocked_rosters br on (cc.stem_name = br.stem_name OR cc.acad_org = br.acad_org OR cc.acad_group = br.acad_group)" +
								   " inner join nyu_t_acad_session sess on sess.strm = cc.strm and sess.acad_career = cc.acad_career" +
								   " where br.action = ?" +
								   "   and sess.cle_eid = ?" +
								   "   and br.system = 'CLASSES'" +
								   "   and replace(cc.stem_name, ':', '_') in (" + placeholders + ")");
			ps.setString(1, action);
			ps.setString(2, termEid);
			for (int i=0; i<providerIds.size(); i++) {
				ps.setString(i+3, providerIds.get(i).replace(":", "_"));
			}

			ResultSet rs = null;
			try {
				rs = ps.executeQuery();
				while (rs.next()) {
					boolean matched = true;

					if (equalsAndNonNull(rs.getString("rule_stem_name"), rs.getString("roster_stem_name"))) {
						// If the stem names match, that's an immediate match.  Nothing more to check.
					} else {
						// Otherwise, AND together the non-null criteria from our rules
						for (String field : new String[] { "strm", "department", "school" }) {
							if ("department".equals(field) && "ALL_DEPARTMENTS".equals(rs.getString("rule_department"))) {
								// Any department is OK
								continue;
							}

							matched &= equalsAndNonNull(rs.getString("rule_" + field), rs.getString("roster_" + field));
						}
					}

					if (matched) {
						result.add(rs.getString("roster_id"));
					}
				}
			} finally {
				if (rs != null) { rs.close(); }
				if (ps != null) { ps.close(); }
			}
		} catch (SQLException e) {
			M_log.warn(this + ".getMatchedProviderIds: " + e);
		} finally {
			sqlService.returnConnection(db);
		}

		return result;
	}
}
