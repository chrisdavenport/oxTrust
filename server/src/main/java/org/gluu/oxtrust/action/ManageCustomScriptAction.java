/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.action;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.gluu.oxtrust.ldap.service.ApplianceService;
import org.gluu.oxtrust.ldap.service.OrganizationService;
import org.gluu.oxtrust.model.SimpleCustomPropertiesListModel;
import org.gluu.oxtrust.model.SimplePropertiesListModel;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.gluu.site.ldap.persistence.exception.LdapMappingException;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.security.Restrict;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.international.StatusMessage.Severity;
import org.jboss.seam.international.StatusMessages;
import org.jboss.seam.log.Log;
import org.xdi.config.oxtrust.ApplicationConfiguration;
import org.xdi.model.AuthenticationScriptUsageType;
import org.xdi.model.ProgrammingLanguage;
import org.xdi.model.ScriptLocationType;
import org.xdi.model.SimpleCustomProperty;
import org.xdi.model.SimpleProperty;
import org.xdi.model.custom.script.CustomScriptType;
import org.xdi.model.custom.script.model.CustomScript;
import org.xdi.model.custom.script.model.auth.AuthenticationCustomScript;
import org.xdi.service.custom.script.AbstractCustomScriptService;
import org.xdi.util.INumGenerator;
import org.xdi.util.OxConstants;
import org.xdi.util.StringHelper;

/**
 * Add/Modify custom script configurations
 * 
 * @author Yuriy Movchan Date: 12/29/2014
 */
@Name("manageCustomScriptAction")
@Scope(ScopeType.CONVERSATION)
@Restrict("#{identity.loggedIn}")
public class ManageCustomScriptAction implements SimplePropertiesListModel, SimpleCustomPropertiesListModel, Serializable {

	private static final long serialVersionUID = -3823022039248381963L;

	@Logger
	private Log log;

	@In
	private StatusMessages statusMessages;

	@In
	private OrganizationService organizationService;

	@In
	private ApplianceService applianceService;

	@In(value = "customScriptService")
	private AbstractCustomScriptService customScriptService;

	@In
	private FacesMessages facesMessages;

	private Map<CustomScriptType, List<CustomScript>> customScriptsByTypes;

	private boolean initialized;
	
	@In(value = "#{oxTrustConfiguration.applicationConfiguration}")
	private ApplicationConfiguration applicationConfiguration;
	
	@Restrict("#{s:hasPermission('configuration', 'access')}")
	public String modify() {
		if (this.initialized) {
			return OxTrustConstants.RESULT_SUCCESS;
		}
		
		CustomScriptType[] allowedCustomScriptTypes = this.applianceService.getCustomScriptTypes();

		this.customScriptsByTypes = new HashMap<CustomScriptType, List<CustomScript>>();
		for (CustomScriptType customScriptType : allowedCustomScriptTypes) {
			this.customScriptsByTypes.put(customScriptType, new ArrayList<CustomScript>());
		}

		try {
			List<CustomScript> customScripts = customScriptService.findCustomScripts(Arrays.asList(allowedCustomScriptTypes));
			
			for (CustomScript customScript : customScripts) {
				CustomScriptType customScriptType = customScript.getScriptType();
				List<CustomScript> customScriptsByType = this.customScriptsByTypes.get(customScriptType);
				
				CustomScript typedCustomScript = customScript;
				if (CustomScriptType.PERSON_AUTHENTICATION == customScriptType) {
					typedCustomScript = new AuthenticationCustomScript(customScript);
				}

				if (typedCustomScript.getConfigurationProperties() == null) {
					typedCustomScript.setConfigurationProperties(new ArrayList<SimpleCustomProperty>());
				}
				
				if (typedCustomScript.getModuleProperties() == null) {
					typedCustomScript.setModuleProperties(new ArrayList<SimpleCustomProperty>());
				}

				customScriptsByType.add(typedCustomScript);
			}
		} catch (Exception ex) {
			log.error("Failed to load custom scripts ", ex);

			return OxTrustConstants.RESULT_FAILURE;
		}

		this.initialized = true;

		return OxTrustConstants.RESULT_SUCCESS;
	}

	@Restrict("#{s:hasPermission('configuration', 'access')}")
	public String save() {
		try {
			List<CustomScript> oldCustomScripts = customScriptService.findCustomScripts(Arrays.asList(this.applianceService.getCustomScriptTypes()), "dn", "inum");

			List<String> updatedInums = new ArrayList<String>();

			for (Entry<CustomScriptType, List<CustomScript>> customScriptsByType : this.customScriptsByTypes.entrySet()) {
				List<CustomScript> customScripts = customScriptsByType.getValue();

				for (CustomScript customScript : customScripts) {
					
					String configId = customScript.getName();
					if (StringHelper.equalsIgnoreCase(configId, OxConstants.SCRIPT_TYPE_INTERNAL_RESERVED_NAME)) {
						facesMessages.add(Severity.ERROR, "'{0}' is reserved script name", configId);
						return OxTrustConstants.RESULT_FAILURE;
					}

					customScript.setRevision(customScript.getRevision() + 1);

					boolean update = true;
					String dn = customScript.getDn();
					String customScriptId = customScript.getInum();
					if (StringHelper.isEmpty(dn)) {
						String basedInum = OrganizationService.instance().getOrganizationInum();
						customScriptId = basedInum + "!" + INumGenerator.generate(2);
						dn = customScriptService.buildDn(customScriptId);
	
						customScript.setDn(dn);
						customScript.setInum(customScriptId);
						update = false;
					};

					customScript.setDn(dn);
					customScript.setInum(customScriptId);
					
					if (ScriptLocationType.LDAP == customScript.getLocationType()) {
						customScript.removeModuleProperty(CustomScript.LOCATION_PATH_MODEL_PROPERTY);
					}
					
					if (customScript.getConfigurationProperties().size() == 0) {
						customScript.setConfigurationProperties(null);
					}
					
					if (customScript.getModuleProperties().size() == 0) {
						customScript.setModuleProperties(null);
					}
					
					updatedInums.add(customScriptId);
	
					if (update) {
						customScriptService.update(customScript);
					} else {
						customScriptService.add(customScript);
					}
				}
			}
			
			// Remove removed scripts
			for (CustomScript oldCustomScript : oldCustomScripts) {
				if (!updatedInums.contains(oldCustomScript.getInum())) {
					customScriptService.remove(oldCustomScript);
				}
			}
		} catch (LdapMappingException ex) {
			log.error("Failed to update custom scripts", ex);
			return OxTrustConstants.RESULT_FAILURE;
		}

		reset();

		return OxTrustConstants.RESULT_SUCCESS;
	}

	private void reset() {
		this.initialized = false;
	}


	@Restrict("#{s:hasPermission('configuration', 'access')}")
	public void cancel() throws Exception {
	}

	public Map<CustomScriptType, List<CustomScript>> getCustomScriptsByTypes() {
		return this.customScriptsByTypes;
	}

	public String getId(Object obj) {
		return "c" + System.identityHashCode(obj) + "Id";
	}

	public void addCustomScript(CustomScriptType scriptType) {
		List<CustomScript> customScriptsByType = this.customScriptsByTypes.get(scriptType);

		CustomScript customScript;
		if (CustomScriptType.PERSON_AUTHENTICATION == scriptType) {
			AuthenticationCustomScript authenticationCustomScript = new AuthenticationCustomScript();
			authenticationCustomScript.setModuleProperties(new ArrayList<SimpleCustomProperty>());
			authenticationCustomScript.setUsageType(AuthenticationScriptUsageType.INTERACTIVE);

			customScript = authenticationCustomScript;
		} else {
			customScript = new CustomScript();
			customScript.setModuleProperties(new ArrayList<SimpleCustomProperty>());
		}

		customScript.setLocationType(ScriptLocationType.LDAP);
		customScript.setScriptType(scriptType);
		customScript.setProgrammingLanguage(ProgrammingLanguage.PYTHON);
		customScript.setConfigurationProperties(new ArrayList<SimpleCustomProperty>());

		customScriptsByType.add(customScript);
	}

	public void removeCustomScript(CustomScript removeCustomScript) {
		for (Entry<CustomScriptType, List<CustomScript>> customScriptsByType : this.customScriptsByTypes.entrySet()) {
			List<CustomScript> customScripts = customScriptsByType.getValue();
			for (Iterator<CustomScript> iterator = customScripts.iterator(); iterator.hasNext();) {
				CustomScript customScript = iterator.next();
				if (System.identityHashCode(removeCustomScript) == System.identityHashCode(customScript)) {
					iterator.remove();
					return;
				}
			}
		}
	}

	@Override
	public void addItemToSimpleProperties(List<SimpleProperty> simpleProperties) {
		if (simpleProperties != null) {
			simpleProperties.add(new SimpleProperty(""));
		}
	}

	@Override
	public void removeItemFromSimpleProperties(List<SimpleProperty> simpleProperties, SimpleProperty simpleProperty) {
		if (simpleProperties != null) {
			simpleProperties.remove(simpleProperty);
		}
	}

	@Override
	public void addItemToSimpleCustomProperties(List<SimpleCustomProperty> simpleCustomProperties) {
		if (simpleCustomProperties != null) {
			simpleCustomProperties.add(new SimpleCustomProperty("", ""));
		}
	}

	@Override
	public void removeItemFromSimpleCustomProperties(List<SimpleCustomProperty> simpleCustomProperties, SimpleCustomProperty simpleCustomProperty) {
		if (simpleCustomProperties != null) {
			simpleCustomProperties.remove(simpleCustomProperty);
		}
	}

	public boolean isInitialized() {
		return initialized;
	}

}
